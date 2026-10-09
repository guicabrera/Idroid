package relay

import (
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Config holds runtime settings for the relay, sourced from environment
// variables so the launchd plist can stay a plain, static file.
type Config struct {
	// ListenAddr is the address the HTTP server binds to, e.g. ":8443".
	// Bind to the tailnet interface in production (see README).
	ListenAddr string

	// DataDir is where device records and item blobs are persisted.
	DataDir string

	// AdminSecret authorizes device-provisioning calls (POST /admin/devices).
	// Required: the relay refuses to start without one.
	AdminSecret string

	// Retention is how long an undelivered item is kept before the cleanup
	// sweep deletes it.
	Retention time.Duration
}

func LoadConfig() (Config, error) {
	cfg := Config{
		ListenAddr:  getEnv("IDROID_LISTEN_ADDR", ":8443"),
		DataDir:     getEnv("IDROID_DATA_DIR", defaultDataDir()),
		AdminSecret: os.Getenv("IDROID_ADMIN_SECRET"),
	}

	retentionDays := getEnv("IDROID_RETENTION_DAYS", "7")
	days, err := strconv.Atoi(retentionDays)
	if err != nil {
		return Config{}, err
	}
	cfg.Retention = time.Duration(days) * 24 * time.Hour

	if cfg.AdminSecret == "" {
		return Config{}, errMissingAdminSecret
	}

	return cfg, nil
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// defaultDataDir avoids requiring root: the relay is meant to run as a
// per-user launchd agent (see launchd/com.idroid.relay.plist), not a
// system daemon, so its default storage lives under the user's own
// Application Support directory.
func defaultDataDir() string {
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, "Library", "Application Support", "idroid-relay")
	}
	return "idroid-relay-data"
}
