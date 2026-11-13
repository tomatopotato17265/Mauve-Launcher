package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type storedAccount struct {
	ID           string    `json:"id"`
	Username     string    `json:"username"`
	AccessToken  string    `json:"accessToken"`
	RefreshToken string    `json:"refreshToken"`
	Expires      time.Time `json:"expires"`
}

type accountsFile struct {
	DefaultUser string          `json:"defaultUser,omitempty"`
	Accounts    []storedAccount `json:"accounts"`
}

type deviceFile struct {
	ID         string    `json:"id"`
	PrivateKey string    `json:"privateKey"`
	Token      string    `json:"token,omitempty"`
	NotAfter   time.Time `json:"notAfter,omitempty"`
}

type authStore struct {
	mu  sync.Mutex
	dir string
}

func newAuthStore() *authStore {
	return &authStore{}
}

func (s *authStore) path(name string) (string, error) {
	if s.dir == "" {
		base, err := os.UserConfigDir()
		if err != nil {
			return "", err
		}
		s.dir = filepath.Join(base, "MauveLauncher")
	}
	return filepath.Join(s.dir, name), nil
}

func (s *authStore) read(name string, out any) (bool, error) {
	p, err := s.path(name)
	if err != nil {
		return false, err
	}
	data, err := os.ReadFile(p)
	if errors.Is(err, fs.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, json.Unmarshal(data, out)
}

func (s *authStore) write(name string, v any) error {
	p, err := s.path(name)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(s.dir, name+".*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), p)
}

func (s *authStore) accounts() ([]storedAccount, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var f accountsFile
	if _, err := s.read("accounts.json", &f); err != nil {
		return nil, err
	}
	out := make([]storedAccount, 0, len(f.Accounts))
	for _, a := range f.Accounts {
		if a.ID == f.DefaultUser {
			out = append([]storedAccount{a}, out...)
		} else {
			out = append(out, a)
		}
	}
	return out, nil
}

func (s *authStore) saveAccount(acc storedAccount) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	var f accountsFile
	if _, err := s.read("accounts.json", &f); err != nil {
		return err
	}
	replaced := false
	for i := range f.Accounts {
		if f.Accounts[i].ID == acc.ID {
			f.Accounts[i] = acc
			replaced = true
		}
	}
	if !replaced {
		f.Accounts = append(f.Accounts, acc)
	}
	f.DefaultUser = acc.ID
	return s.write("accounts.json", f)
}

func (s *authStore) device() (*deviceFile, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var f deviceFile
	ok, err := s.read("device.json", &f)
	if err != nil || !ok {
		return nil, err
	}
	return &f, nil
}

func (s *authStore) saveDevice(f *deviceFile) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.write("device.json", f)
}
