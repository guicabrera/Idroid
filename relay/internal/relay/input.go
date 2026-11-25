package relay

import (
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Shared mouse and keyboard (ADR-0008): a live, two-way websocket pipe
// between two devices of the same group. The Mac opens GET /input/{phone_id};
// the relay pushes "input_request" (sender_id = Mac) to the phone, which opens
// GET /input/{mac_id}. The two sockets are then bridged frame by frame.
// Nothing is stored or inspected.

// inputWaitTimeout bounds how long one side waits for its peer to join. The
// Mac reconnects on its own, which re-sends the request.
const inputWaitTimeout = 30 * time.Second

type inputWaiter struct {
	conn   *websocket.Conn
	paired chan *websocket.Conn
}

type inputPipes struct {
	mu      sync.Mutex
	waiting map[string]*inputWaiter // key "from>to"
}

var pipes = &inputPipes{waiting: make(map[string]*inputWaiter)}

func (s *Server) handleInput(w http.ResponseWriter, r *http.Request) {
	caller := deviceFromContext(r)
	peerID := r.PathValue("device_id")
	if peerID == caller.ID || !s.devices.SameGroup(caller.ID, peerID) {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	if !s.hub.IsOnline(peerID) {
		http.Error(w, "device offline", http.StatusServiceUnavailable)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("input upgrade %s -> %s: %v", caller.ID, peerID, err)
		return
	}

	pipes.mu.Lock()
	if other, ok := pipes.waiting[peerID+">"+caller.ID]; ok {
		delete(pipes.waiting, peerID+">"+caller.ID)
		pipes.mu.Unlock()
		other.paired <- conn
		return // the waiting side's handler owns the bridge
	}
	key := caller.ID + ">" + peerID
	if old, ok := pipes.waiting[key]; ok {
		old.conn.Close()
	}
	me := &inputWaiter{conn: conn, paired: make(chan *websocket.Conn, 1)}
	pipes.waiting[key] = me
	pipes.mu.Unlock()

	s.hub.send(peerID, NotifyEvent{Event: "input_request", SenderID: caller.ID})

	var peer *websocket.Conn
	select {
	case peer = <-me.paired:
	case <-time.After(inputWaitTimeout):
	}
	if peer == nil {
		pipes.mu.Lock()
		if pipes.waiting[key] == me {
			delete(pipes.waiting, key)
		}
		pipes.mu.Unlock()
		// A pairing may have raced the timeout.
		select {
		case peer = <-me.paired:
		default:
			conn.Close()
			return
		}
	}
	log.Printf("input pipe %s <-> %s open", caller.ID, peerID)
	bridge(conn, peer)
	log.Printf("input pipe %s <-> %s closed", caller.ID, peerID)
}

// bridge copies frames both ways until either side closes, then closes both.
func bridge(a, b *websocket.Conn) {
	done := make(chan struct{}, 2)
	pump := func(src, dst *websocket.Conn) {
		for {
			kind, data, err := src.ReadMessage()
			if err != nil {
				break
			}
			if err := dst.WriteMessage(kind, data); err != nil {
				break
			}
		}
		done <- struct{}{}
	}
	go pump(a, b)
	go pump(b, a)
	<-done
	a.Close()
	b.Close()
	<-done
}
