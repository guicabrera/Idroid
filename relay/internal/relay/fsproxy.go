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

// fsResponseTimeout bounds how long a caller waits for the target to start
// answering; the transfer itself, once started, has no deadline.
const fsResponseTimeout = 20 * time.Second

type fsReply struct {
	r    *http.Request
	done chan struct{}
}

type fsPending struct {
	targetID string
	reply    chan fsReply
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

// abandon drops a request the caller stopped waiting for. It reports false
// when a responder has already claimed it, in which case its reply is on
// the way and the caller must still receive it.
func (p *fsProxy) abandon(id string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, ok := p.pending[id]; !ok {
		return false
	}
	delete(p.pending, id)
	return true
}

// handleFSRequest serves GET /fs/{device_id}/{op}?path=... where op is
// "list" (JSON directory listing) or "content" (file bytes).
func (s *Server) handleFSRequest(w http.ResponseWriter, r *http.Request) {
	caller := deviceFromContext(r)
	targetID := r.PathValue("device_id")
	op := r.PathValue("op")
	if op != "list" && op != "content" {
		http.Error(w, "op must be list or content", http.StatusNotFound)
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
	var reply fsReply
	select {
	case reply = <-pending.reply:
	case <-timer.C:
		if s.fs.abandon(id) {
			log.Printf("fs %s %s -> %s: no response", op, caller.ID, targetID)
			http.Error(w, "device did not respond", http.StatusGatewayTimeout)
			return
		}
		reply = <-pending.reply
	case <-r.Context().Done():
		if s.fs.abandon(id) {
			log.Printf("fs %s %s -> %s: caller went away", op, caller.ID, targetID)
			return
		}
		reply = <-pending.reply
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
