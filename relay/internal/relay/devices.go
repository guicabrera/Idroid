package relay

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Device is a paired client (an Android phone, a Mac, an iPad) known to the
// relay.
//
// Devices get a token one of two ways: the PIN/QR pairing flow in
// pairing.go (POST /pair/start + POST /pair/claim, called by the apps
// themselves) or the admin-authorized POST /admin/devices, which remains for
// scripting and as a fallback.
//
// Every device belongs to exactly one group: the set of devices that can
// send to each other. A new device starts alone in its own group; pairing
// merges the two devices' groups, so pairing the phone with the Mac mini and
// then with the MacBook puts all three in one group.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Token     string    `json:"token"`
	GroupID   string    `json:"group_id"`
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
		if d.GroupID == "" {
			// Pre-group records: each starts alone and has to pair again.
			d.GroupID = d.ID
		}
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

	d := &Device{ID: id, Name: name, Token: token, GroupID: id, CreatedAt: time.Now().UTC()}
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

// Members returns every device in groupID, oldest first.
func (ds *deviceStore) Members(groupID string) []*Device {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	var members []*Device
	for _, d := range ds.byID {
		if d.GroupID == groupID {
			members = append(members, d)
		}
	}
	sort.Slice(members, func(i, j int) bool { return members[i].CreatedAt.Before(members[j].CreatedAt) })
	return members
}

// MergeGroups moves every device in group `from` into group `into`.
func (ds *deviceStore) MergeGroups(into, from string) error {
	if into == from {
		return nil
	}
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.byID {
		if d.GroupID == from {
			d.GroupID = into
		}
	}
	return ds.persist()
}

// SameGroup reports whether both devices exist and can send to each other.
func (ds *deviceStore) SameGroup(a, b string) bool {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	da, okA := ds.byID[a]
	db, okB := ds.byID[b]
	return okA && okB && da.GroupID == db.GroupID
}

// Remove deletes a device, invalidating its token; it has to pair again.
func (ds *deviceStore) Remove(id string) error {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	d, ok := ds.byID[id]
	if !ok {
		return errUnknownDevice
	}
	delete(ds.byID, id)
	delete(ds.byToken, d.Token)
	return ds.persist()
}
