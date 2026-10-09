package relay

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Device is a paired client (an Android phone or a Mac) known to the relay.
//
// Devices get a token one of two ways: the PIN pairing flow in pairing.go
// (POST /pair/start + POST /pair/claim, called by the apps themselves) or
// the admin-authorized POST /admin/devices, which remains for scripting and
// as a fallback. QR code pairing (encoding the same code show by
// /pair/start) is still open per ADR-0001's feature priority list.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Token     string    `json:"token"`
	CreatedAt time.Time `json:"created_at"`
}

type deviceStore struct {
	mu      sync.RWMutex
	path    string
	byID    map[string]*Device
	byToken map[string]*Device
}

func newDeviceStore(dataDir string) (*deviceStore, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	ds := &deviceStore{
		path:    filepath.Join(dataDir, "devices.json"),
		byID:    make(map[string]*Device),
		byToken: make(map[string]*Device),
	}
	if err := ds.load(); err != nil {
		return nil, err
	}
	return ds, nil
}

func (ds *deviceStore) load() error {
	data, err := os.ReadFile(ds.path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var devices []*Device
	if err := json.Unmarshal(data, &devices); err != nil {
		return err
	}
	for _, d := range devices {
		ds.byID[d.ID] = d
		ds.byToken[d.Token] = d
	}
	return nil
}

// persist assumes the caller holds ds.mu.
func (ds *deviceStore) persist() error {
	devices := make([]*Device, 0, len(ds.byID))
	for _, d := range ds.byID {
		devices = append(devices, d)
	}
	data, err := json.MarshalIndent(devices, "", "  ")
	if err != nil {
		return err
	}
	tmp := ds.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, ds.path)
}

// Register mints a new device identity and token. The token is returned
// exactly once here; it is not recoverable afterwards, so the caller must
// deliver it to the client as part of the pairing flow.
func (ds *deviceStore) Register(name string) (*Device, error) {
	id, err := newID(8)
	if err != nil {
		return nil, fmt.Errorf("generate device id: %w", err)
	}
	token, err := newID(32)
	if err != nil {
		return nil, fmt.Errorf("generate device token: %w", err)
	}

	ds.mu.Lock()
	defer ds.mu.Unlock()

	d := &Device{ID: id, Name: name, Token: token, CreatedAt: time.Now().UTC()}
	ds.byID[d.ID] = d
	ds.byToken[d.Token] = d
	if err := ds.persist(); err != nil {
		return nil, err
	}
	return d, nil
}

func (ds *deviceStore) Authenticate(token string) (*Device, error) {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	d, ok := ds.byToken[token]
	if !ok {
		return nil, errUnknownDevice
	}
	return d, nil
}

func (ds *deviceStore) Exists(id string) bool {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	_, ok := ds.byID[id]
	return ok
}

func (ds *deviceStore) Get(id string) (*Device, bool) {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	d, ok := ds.byID[id]
	return d, ok
}
