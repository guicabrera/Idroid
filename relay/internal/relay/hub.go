package relay

import (
	"encoding/json"
	"sync"

	"github.com/gorilla/websocket"
)

// NotifyEvent is pushed over a device's websocket connection so a connected
// client doesn't have to poll: "new_item" when something lands in its inbox
// (GET /inbox), "devices_changed" when a device joins or leaves its group
// (GET /devices).
type NotifyEvent struct {
	Event  string `json:"event"`
	ItemID string `json:"item_id"`
}

// hub tracks live websocket connections per device ID. A device may have
// more than one connection (e.g. app open on two Macs isn't expected, but we
// don't assume otherwise).
type hub struct {
	mu    sync.Mutex
	conns map[string]map[*websocket.Conn]struct{}
	// gorilla/websocket allows one concurrent writer per connection; pushes
	// are rare, so one lock for all of them is enough.
	writeMu sync.Mutex
}

func newHub() *hub {
	return &hub{conns: make(map[string]map[*websocket.Conn]struct{})}
}

func (h *hub) add(deviceID string, conn *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.conns[deviceID] == nil {
		h.conns[deviceID] = make(map[*websocket.Conn]struct{})
	}
	h.conns[deviceID][conn] = struct{}{}
}

func (h *hub) remove(deviceID string, conn *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if set, ok := h.conns[deviceID]; ok {
		delete(set, conn)
		if len(set) == 0 {
			delete(h.conns, deviceID)
		}
	}
}

// IsOnline reports whether the device currently holds a websocket open.
func (h *hub) IsOnline(deviceID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns[deviceID]) > 0
}

// NotifyNewItem pushes a best-effort notification to every live connection
// for a device. Delivery isn't guaranteed; GET /inbox remains the source of
// truth, this is purely a latency optimization.
func (h *hub) NotifyNewItem(deviceID, itemID string) {
	h.send(deviceID, NotifyEvent{Event: "new_item", ItemID: itemID})
}

// NotifyDevicesChanged tells each device to refetch GET /devices.
func (h *hub) NotifyDevicesChanged(deviceIDs []string) {
	for _, id := range deviceIDs {
		h.send(id, NotifyEvent{Event: "devices_changed"})
	}
}

func (h *hub) send(deviceID string, event NotifyEvent) {
	h.mu.Lock()
	conns := make([]*websocket.Conn, 0, len(h.conns[deviceID]))
	for c := range h.conns[deviceID] {
		conns = append(conns, c)
	}
	h.mu.Unlock()

	if len(conns) == 0 {
		return
	}
	payload, err := json.Marshal(event)
	if err != nil {
		return
	}
	h.writeMu.Lock()
	defer h.writeMu.Unlock()
	for _, c := range conns {
		_ = c.WriteMessage(websocket.TextMessage, payload)
	}
}
