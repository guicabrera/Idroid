package relay

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/gorilla/websocket"
)

// maxUploadBytes bounds a single item's payload. Generous for personal use
// (phone photos, short clips); revisit if large video transfers matter.
const maxUploadBytes = 512 << 20 // 512 MiB

type Server struct {
	cfg     Config
	devices *deviceStore
	items   *itemStore
	hub     *hub
	pairing *pairingStore
	mux     *http.ServeMux
}

func NewServer(cfg Config, devices *deviceStore, items *itemStore, h *hub, pairing *pairingStore) *Server {
	s := &Server{cfg: cfg, devices: devices, items: items, hub: h, pairing: pairing, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) { s.mux.ServeHTTP(w, r) }

func (s *Server) routes() {
	s.mux.HandleFunc("GET /healthz", s.handleHealth)
	s.mux.HandleFunc("POST /admin/devices", s.handleAdminRegisterDevice)

	s.mux.HandleFunc("POST /pair/start", s.handlePairStart)
	s.mux.HandleFunc("POST /pair/claim", s.handlePairClaim)
	s.mux.HandleFunc("GET /pair/status/{session_id}", s.handlePairStatus)

	s.mux.Handle("POST /upload", s.withAuth(s.handleUpload))
	s.mux.Handle("GET /inbox", s.withAuth(s.handleInboxList))
	s.mux.Handle("GET /inbox/{id}/content", s.withAuth(s.handleInboxContent))
	s.mux.Handle("DELETE /inbox/{id}", s.withAuth(s.handleInboxDelete))
	s.mux.Handle("GET /ws", s.withAuth(s.handleWebSocket))
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// --- Admin: device provisioning -------------------------------------------------

type registerDeviceRequest struct {
	Name string `json:"name"`
}

// handleAdminRegisterDevice mints a new device token. This stands in for the
// QR/PIN pairing flow described in ADR-0001: the apps are expected to call
// this once (or have an operator call it on their behalf) and hand the
// resulting token to the device out of band.
func (s *Server) handleAdminRegisterDevice(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Admin-Secret") != s.cfg.AdminSecret {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}

	var req registerDeviceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Name == "" {
		http.Error(w, "body must be JSON with a non-empty \"name\"", http.StatusBadRequest)
		return
	}

	device, err := s.devices.Register(req.Name)
	if err != nil {
		log.Printf("register device: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusCreated, device)
}

// --- Pairing (PIN-based, no admin secret needed) -----------------------------
//
// Replaces having an operator run POST /admin/devices twice and copy tokens
// around by hand: one device starts a session and shows the resulting code
// to the user (read aloud, typed in, or later a QR code); the other device
// claims it. See pairing.go for the session lifecycle and ADR-0001 for why a
// shared secret between the two devices is needed at all (tailnet
// membership alone isn't enough to prove two apps belong to the same
// person).

type pairStartRequest struct {
	Name string `json:"name"`
}

type pairStartResponse struct {
	Device  *Device `json:"device"`
	Pairing struct {
		SessionID string    `json:"session_id"`
		Code      string    `json:"code"`
		ExpiresAt time.Time `json:"expires_at"`
	} `json:"pairing"`
}

func (s *Server) handlePairStart(w http.ResponseWriter, r *http.Request) {
	var req pairStartRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Name == "" {
		http.Error(w, "body must be JSON with a non-empty \"name\"", http.StatusBadRequest)
		return
	}

	device, err := s.devices.Register(req.Name)
	if err != nil {
		log.Printf("register device: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	session, err := s.pairing.Start(device.ID)
	if err != nil {
		log.Printf("start pairing session: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	resp := pairStartResponse{Device: device}
	resp.Pairing.SessionID = session.SessionID
	resp.Pairing.Code = session.Code
	resp.Pairing.ExpiresAt = session.ExpiresAt
	writeJSON(w, http.StatusCreated, resp)
}

type pairClaimRequest struct {
	Code string `json:"code"`
	Name string `json:"name"`
}

type pairPeer struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type pairClaimResponse struct {
	Device *Device  `json:"device"`
	Peer   pairPeer `json:"peer"`
}

func (s *Server) handlePairClaim(w http.ResponseWriter, r *http.Request) {
	var req pairClaimRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Code == "" || req.Name == "" {
		http.Error(w, "body must be JSON with a non-empty \"code\" and \"name\"", http.StatusBadRequest)
		return
	}

	session, err := s.pairing.ClaimCode(req.Code)
	if errors.Is(err, errPairingCodeInvalid) {
		http.Error(w, "pairing code invalid or expired", http.StatusGone)
		return
	}
	if err != nil {
		log.Printf("claim pairing code: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	device, err := s.devices.Register(req.Name)
	if err != nil {
		log.Printf("register device: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	s.pairing.SetPeer(session.SessionID, device.ID, device.Name)

	initiator, _ := s.devices.Get(session.DeviceID)
	peer := pairPeer{ID: session.DeviceID}
	if initiator != nil {
		peer.Name = initiator.Name
	}
	writeJSON(w, http.StatusCreated, pairClaimResponse{Device: device, Peer: peer})
}

type pairStatusResponse struct {
	Claimed bool      `json:"claimed"`
	Peer    *pairPeer `json:"peer,omitempty"`
}

func (s *Server) handlePairStatus(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("session_id")
	session, err := s.pairing.Status(sessionID)
	if errors.Is(err, errPairingSessionNotFound) {
		http.Error(w, "pairing session not found or expired", http.StatusNotFound)
		return
	}
	if err != nil {
		log.Printf("pairing status: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	resp := pairStatusResponse{Claimed: session.Claimed}
	if session.Claimed {
		resp.Peer = &pairPeer{ID: session.PeerDeviceID, Name: session.PeerName}
	}
	writeJSON(w, http.StatusOK, resp)
}

// --- Auth middleware --------------------------------------------------------

type ctxKey int

const deviceCtxKey ctxKey = 0

func (s *Server) withAuth(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" {
			http.Error(w, "missing bearer token", http.StatusUnauthorized)
			return
		}
		device, err := s.devices.Authenticate(token)
		if err != nil {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), deviceCtxKey, device)
		next(w, r.WithContext(ctx))
	})
}

func bearerToken(r *http.Request) string {
	const prefix = "Bearer "
	auth := r.Header.Get("Authorization")
	if len(auth) > len(prefix) && auth[:len(prefix)] == prefix {
		return auth[len(prefix):]
	}
	// Browsers' WebSocket API can't set custom headers during the
	// handshake, so /ws also accepts the token as a query parameter.
	return r.URL.Query().Get("token")
}

func deviceFromContext(r *http.Request) *Device {
	d, _ := r.Context().Value(deviceCtxKey).(*Device)
	return d
}

// --- Upload / inbox -----------------------------------------------------------

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	sender := deviceFromContext(r)

	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBytes)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		http.Error(w, "invalid multipart form: "+err.Error(), http.StatusBadRequest)
		return
	}

	recipientID := r.FormValue("recipient")
	if recipientID == "" || !s.devices.Exists(recipientID) {
		http.Error(w, "unknown recipient device", http.StatusBadRequest)
		return
	}

	kind := ItemKind(r.FormValue("kind"))
	switch kind {
	case ItemKindFile, ItemKindText, ItemKindClipboard:
	default:
		http.Error(w, "kind must be one of: file, text, clipboard", http.StatusBadRequest)
		return
	}

	file, header, err := r.FormFile("payload")
	if err != nil {
		http.Error(w, "missing \"payload\" file part", http.StatusBadRequest)
		return
	}
	defer file.Close()

	filename := r.FormValue("filename")
	if filename == "" {
		filename = header.Filename
	}
	contentType := r.FormValue("content_type")
	if contentType == "" {
		contentType = header.Header.Get("Content-Type")
	}
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	meta, err := s.items.Save(sender.ID, recipientID, kind, filename, contentType, s.cfg.Retention, file)
	if err != nil {
		log.Printf("save item: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	s.hub.NotifyNewItem(recipientID, meta.ID)
	writeJSON(w, http.StatusCreated, meta)
}

func (s *Server) handleInboxList(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r)
	items := s.items.ListForRecipient(device.ID)
	writeJSON(w, http.StatusOK, items)
}

func (s *Server) handleInboxContent(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r)
	id := r.PathValue("id")

	meta, err := s.items.Get(id)
	if errors.Is(err, errItemNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if meta.RecipientID != device.ID {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	payload, err := s.items.OpenPayload(id)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	defer payload.Close()

	w.Header().Set("Content-Type", meta.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(meta.Size, 10))
	if meta.Filename != "" {
		w.Header().Set("Content-Disposition", `attachment; filename="`+meta.Filename+`"`)
	}
	_, _ = io.Copy(w, payload)
}

func (s *Server) handleInboxDelete(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r)
	id := r.PathValue("id")

	meta, err := s.items.Get(id)
	if errors.Is(err, errItemNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if meta.RecipientID != device.ID {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err := s.items.Delete(id); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- WebSocket push -----------------------------------------------------------

var upgrader = websocket.Upgrader{
	// The relay only ever sits behind the tailnet; any origin reaching it
	// already passed Tailscale's own access control, so we don't add a
	// second origin check here.
	CheckOrigin: func(r *http.Request) bool { return true },
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r)

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("websocket upgrade for device %s: %v", device.ID, err)
		return
	}
	s.hub.add(device.ID, conn)
	defer func() {
		s.hub.remove(device.ID, conn)
		conn.Close()
	}()

	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	// This connection only carries server->client pushes; drain and discard
	// anything the client sends (including pings/pongs) until it closes.
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			return
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
