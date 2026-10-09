package relay

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// ItemKind distinguishes payload types so clients can render them
// appropriately; the relay itself treats all payloads as opaque bytes.
type ItemKind string

const (
	ItemKindFile      ItemKind = "file"
	ItemKindText      ItemKind = "text"
	ItemKindClipboard ItemKind = "clipboard"
)

// ItemMeta describes one item sitting in a recipient's inbox: either an
// explicit share (file/text/link) or a clipboard-sync push (see ADR-0002),
// queued because the recipient wasn't reachable directly.
type ItemMeta struct {
	ID          string    `json:"id"`
	SenderID    string    `json:"sender_id"`
	RecipientID string    `json:"recipient_id"`
	Kind        ItemKind  `json:"kind"`
	Filename    string    `json:"filename,omitempty"`
	ContentType string    `json:"content_type"`
	Size        int64     `json:"size"`
	CreatedAt   time.Time `json:"created_at"`
	ExpiresAt   time.Time `json:"expires_at"`
}

type itemStore struct {
	mu  sync.RWMutex
	dir string
	// index caches metadata in memory; payload bytes stay on disk and are
	// only read on demand.
	index map[string]*ItemMeta
}

func newItemStore(dataDir string) (*itemStore, error) {
	dir := filepath.Join(dataDir, "items")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &itemStore{dir: dir, index: make(map[string]*ItemMeta)}
	if err := s.loadIndex(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *itemStore) loadIndex() error {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		meta, err := s.readMeta(e.Name())
		if err != nil {
			// Skip corrupt/partial entries rather than failing startup.
			continue
		}
		s.index[meta.ID] = meta
	}
	return nil
}

func (s *itemStore) itemDir(id string) string     { return filepath.Join(s.dir, id) }
func (s *itemStore) metaPath(id string) string    { return filepath.Join(s.itemDir(id), "meta.json") }
func (s *itemStore) payloadPath(id string) string { return filepath.Join(s.itemDir(id), "payload") }

func (s *itemStore) readMeta(id string) (*ItemMeta, error) {
	data, err := os.ReadFile(s.metaPath(id))
	if err != nil {
		return nil, err
	}
	var meta ItemMeta
	if err := json.Unmarshal(data, &meta); err != nil {
		return nil, err
	}
	return &meta, nil
}

// Save persists a new item's metadata and streams its payload to disk,
// returning the finalized metadata (ID, timestamps, size filled in).
func (s *itemStore) Save(senderID, recipientID string, kind ItemKind, filename, contentType string, retention time.Duration, body io.Reader) (*ItemMeta, error) {
	id, err := newID(16)
	if err != nil {
		return nil, fmt.Errorf("generate item id: %w", err)
	}

	if err := os.MkdirAll(s.itemDir(id), 0o700); err != nil {
		return nil, err
	}

	f, err := os.OpenFile(s.payloadPath(id), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, err
	}
	size, err := io.Copy(f, body)
	closeErr := f.Close()
	if err != nil {
		return nil, err
	}
	if closeErr != nil {
		return nil, closeErr
	}

	now := time.Now().UTC()
	meta := &ItemMeta{
		ID:          id,
		SenderID:    senderID,
		RecipientID: recipientID,
		Kind:        kind,
		Filename:    filename,
		ContentType: contentType,
		Size:        size,
		CreatedAt:   now,
		ExpiresAt:   now.Add(retention),
	}

	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(s.metaPath(id), data, 0o600); err != nil {
		return nil, err
	}

	s.mu.Lock()
	s.index[id] = meta
	s.mu.Unlock()

	return meta, nil
}

func (s *itemStore) Get(id string) (*ItemMeta, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	meta, ok := s.index[id]
	if !ok {
		return nil, errItemNotFound
	}
	return meta, nil
}

func (s *itemStore) OpenPayload(id string) (*os.File, error) {
	return os.Open(s.payloadPath(id))
}

// ListForRecipient returns pending items for a device, oldest first.
func (s *itemStore) ListForRecipient(recipientID string) []*ItemMeta {
	s.mu.RLock()
	defer s.mu.RUnlock()

	items := make([]*ItemMeta, 0)
	for _, meta := range s.index {
		if meta.RecipientID == recipientID {
			items = append(items, meta)
		}
	}
	sort.Slice(items, func(i, j int) bool { return items[i].CreatedAt.Before(items[j].CreatedAt) })
	return items
}

func (s *itemStore) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.index[id]; !ok {
		return errItemNotFound
	}
	delete(s.index, id)
	return os.RemoveAll(s.itemDir(id))
}

// CleanupExpired deletes items past their retention window. Call on a
// periodic ticker (see main.go); returns the deleted item IDs for logging.
func (s *itemStore) CleanupExpired(now time.Time) []string {
	s.mu.Lock()
	var expired []string
	for id, meta := range s.index {
		if now.After(meta.ExpiresAt) {
			expired = append(expired, id)
			delete(s.index, id)
		}
	}
	s.mu.Unlock()

	for _, id := range expired {
		_ = os.RemoveAll(s.itemDir(id))
	}
	return expired
}
