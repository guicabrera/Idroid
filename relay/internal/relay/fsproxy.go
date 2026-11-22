package relay

import (
	"io"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Live file browsing (ADR-0004): one device lists or downloads files from a
// folder another device has shared. Nothing is stored here — the relay
// forwards the request to the target over its websocket ("fs_request") and
// pipes the target's answer (POST /fs/respond/{id}) straight back to the
// caller. Both apps already hold a websocket open to the relay, so neither
// has to accept inbound connections.
//
// Uploads ("PUT /fs/{device_id}/upload") run the other way round: the target
// pulls the caller's body with GET /fs/pull/{id}, writes it, then answers as
// usual. Still nothing touches the relay's disk.

// fsResponseTimeout bounds how long a caller waits for the target to start
// answering (or, for an upload, to start and to finish pulling the body); the
// transfer itself, once started, has no deadline.
const fsResponseTimeout = 20 * time.Second

type fsReply struct {
	r    *http.Request
	done chan struct{}
}

type fsPending struct {
	targetID string
	reply    chan fsReply

	// Set for uploads only: the caller's request, whose body the target
	// pulls once. started and pulled are closed when that pull begins and ends.
	upload  *http.Request
	started chan struct{}
	pulled  chan struct{}
	pulling bool // guarded by fsProxy.mu
	wasPull bool // guarded by fsProxy.mu
}

type fsProxy struct {
	mu      sync.Mutex
	pending map[string]*fsPending
}

func newFSProxy() *fsProxy {
	return &fsProxy{pending: make(map[string]*fsPending)}
}

// claim hands a pending request to its responder exactly once; a request the
// caller already gave up on (or one addressed to another device) isn't found.
func (p *fsProxy) claim(id, responderID string) (*fsPending, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	pending, ok := p.pending[id]
	if !ok || pending.targetID != responderID {
		return nil, false
	}
	delete(p.pending, id)
	return pending, true
}

// startPull hands an upload's body to its target, once.
func (p *fsProxy) startPull(id, responderID string) (*fsPending, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	pending, ok := p.pending[id]
	if !ok || pending.targetID != responderID || pending.upload == nil || pending.wasPull {
		return nil, false
	}
	pending.wasPull = true
	pending.pulling = true
	close(pending.started)
	return pending, true
}

func (p *fsProxy) endPull(pending *fsPending) {
	p.mu.Lock()
	pending.pulling = false
	p.mu.Unlock()
	close(pending.pulled)
}

// abandon drops a request the caller stopped waiting for. It reports false
// when a responder has already claimed it, in which case its reply is on
// the way and the caller must still receive it, or while the target is
// still reading the caller's upload body, which must outlive that read.
func (p *fsProxy) abandon(id string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	pending, ok := p.pending[id]
	if !ok || pending.pulling {
		return false
	}
	delete(p.pending, id)
	return true
}

// handleFSRequest serves GET /fs/{device_id}/{op}?path=... where op is
// "list" (JSON directory listing) or "content" (file bytes), and
// PUT /fs/{device_id}/upload?path=... (the body becomes that file; the target
// may pick another name if it exists).
func (s *Server) handleFSRequest(w http.ResponseWriter, r *http.Request) {
	caller := deviceFromContext(r)
	targetID := r.PathValue("device_id")
	op := r.PathValue("op")
	isUpload := r.Method == http.MethodPut
	if isUpload && op != "upload" || !isUpload && op != "list" && op != "content" {
		http.Error(w, "op must be list, content or upload", http.StatusNotFound)
		return
	}
	if targetID == caller.ID || !s.devices.SameGroup(caller.ID, targetID) {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	if !s.hub.IsOnline(targetID) {
		log.Printf("fs %s %s -> %s: offline", op, caller.ID, targetID)
		http.Error(w, "device offline", http.StatusServiceUnavailable)
		return
	}

	id, err := newID(16)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	pending := &fsPending{targetID: targetID, reply: make(chan fsReply, 1)}
	if isUpload {
		pending.upload = r
		pending.started = make(chan struct{})
		pending.pulled = make(chan struct{})
	}
	s.fs.mu.Lock()
	s.fs.pending[id] = pending
	s.fs.mu.Unlock()

	path := r.URL.Query().Get("path")
	if path == "" {
		path = "/"
	}
	s.hub.send(targetID, NotifyEvent{Event: "fs_request", RequestID: id, Op: op, Path: path, SenderID: caller.ID})

	timer := time.NewTimer(fsResponseTimeout)
	defer timer.Stop()
	// started and pulled stay nil (never ready) unless this is an upload.
	started, pulled, gone := pending.started, pending.pulled, r.Context().Done()
	var reply fsReply
wait:
	for {
		select {
		case reply = <-pending.reply:
			break wait
		case <-started:
			// The target is reading the upload: no deadline while bytes flow.
			timer.Stop()
			started = nil
		case <-pulled:
			timer.Reset(fsResponseTimeout)
			pulled = nil
		case <-timer.C:
			if s.fs.abandon(id) {
				log.Printf("fs %s %s -> %s: no response", op, caller.ID, targetID)
				http.Error(w, "device did not respond", http.StatusGatewayTimeout)
				return
			}
		case <-gone:
			gone = nil
			if s.fs.abandon(id) {
				log.Printf("fs %s %s -> %s: caller went away", op, caller.ID, targetID)
				return
			}
		}
	}
	defer close(reply.done)

	status := http.StatusOK
	if v, err := strconv.Atoi(reply.r.Header.Get("X-Idroid-Status")); err == nil && v >= 200 && v < 600 {
		status = v
	}
	for _, h := range []string{"Content-Type", "Content-Disposition"} {
		if v := reply.r.Header.Get(h); v != "" {
			w.Header().Set(h, v)
		}
	}
	if reply.r.ContentLength >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(reply.r.ContentLength, 10))
	}
	w.WriteHeader(status)
	n, err := io.Copy(w, reply.r.Body)
	if err != nil {
		log.Printf("fs %s %s -> %s: status %d, copy failed after %d bytes: %v", op, caller.ID, targetID, status, n, err)
	} else {
		log.Printf("fs %s %s -> %s: status %d, %d bytes", op, caller.ID, targetID, status, n)
	}
}

// handleFSRespond serves POST /fs/respond/{request_id}: the target device's
// answer to an fs_request. Headers: X-Idroid-Status (status for the caller,
// default 200), Content-Type, optional Content-Disposition; body is passed
// through as is. It returns once the body has been relayed.
func (s *Server) handleFSRespond(w http.ResponseWriter, r *http.Request) {
	responder := deviceFromContext(r)
	pending, ok := s.fs.claim(r.PathValue("request_id"), responder.ID)
	if !ok {
		http.Error(w, "request not found or expired", http.StatusGone)
		return
	}
	done := make(chan struct{})
	pending.reply <- fsReply{r: r, done: done}
	<-done
	w.WriteHeader(http.StatusNoContent)
}

// handleFSPull serves GET /fs/pull/{request_id}: the target of an upload
// reads the caller's body, streamed through as it arrives.
func (s *Server) handleFSPull(w http.ResponseWriter, r *http.Request) {
	responder := deviceFromContext(r)
	pending, ok := s.fs.startPull(r.PathValue("request_id"), responder.ID)
	if !ok {
		http.Error(w, "upload not found, expired or already read", http.StatusGone)
		return
	}
	defer s.fs.endPull(pending)
	upload := pending.upload
	if v := upload.Header.Get("Content-Type"); v != "" {
		w.Header().Set("Content-Type", v)
	}
	if upload.ContentLength >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(upload.ContentLength, 10))
	}
	if n, err := io.Copy(w, upload.Body); err != nil {
		log.Printf("fs upload pull %s: copy failed after %d bytes: %v", responder.ID, n, err)
	}
}
