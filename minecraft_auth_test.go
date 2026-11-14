package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSignRequestVerifies(t *testing.T) {
	key, err := newDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	at := time.Unix(1_700_000_000, 0)
	body := []byte(`{"hello":"world"}`)

	header, err := signRequest(key, at, "POST", "/device/authenticate", "", body)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.StdEncoding.DecodeString(header)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) != 4+8+64 {
		t.Fatalf("signature header is %d bytes, want 76", len(raw))
	}
	if v := binary.BigEndian.Uint32(raw[0:4]); v != 1 {
		t.Fatalf("policy version = %d, want 1", v)
	}
	wantFiletime := uint64(1_700_000_000+filetimeEpochOffset) * 10_000_000
	if ft := binary.BigEndian.Uint64(raw[4:12]); ft != wantFiletime {
		t.Fatalf("timestamp = %d, want %d", ft, wantFiletime)
	}

	// Rebuild the signed payload independently and check the signature.
	var payload bytes.Buffer
	payload.Write([]byte{0, 0, 0, 1, 0})
	payload.Write(raw[4:12])
	payload.WriteString("\x00POST\x00/device/authenticate\x00\x00")
	payload.Write(body)
	payload.WriteByte(0)
	digest := sha256.Sum256(payload.Bytes())
	r := new(big.Int).SetBytes(raw[12:44])
	s := new(big.Int).SetBytes(raw[44:76])
	if !ecdsa.Verify(&key.Priv.PublicKey, digest[:], r, s) {
		t.Fatal("signature does not verify against the expected payload")
	}
}

func TestDeviceKeyPEMRoundTrip(t *testing.T) {
	key, err := newDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	pemKey, err := key.privatePEM()
	if err != nil {
		t.Fatal(err)
	}
	back, err := parseDeviceKey(key.ID, pemKey)
	if err != nil {
		t.Fatal(err)
	}
	if back.X != key.X || back.Y != key.Y || !back.Priv.Equal(key.Priv) {
		t.Fatal("device key changed after PEM round trip")
	}
	if len(key.X) != 43 || len(key.Y) != 43 {
		t.Fatalf("coordinates should be 32 bytes base64url (43 chars), got %d/%d", len(key.X), len(key.Y))
	}
}

func TestPKCEChallenge(t *testing.T) {
	// RFC 7636 appendix B test vector.
	got := pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
	if want := "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"; got != want {
		t.Fatalf("challenge = %q, want %q", got, want)
	}
}

func TestNewUUID(t *testing.T) {
	id, err := newUUID()
	if err != nil {
		t.Fatal(err)
	}
	if len(id) != 36 || id[14] != '4' || !strings.ContainsRune("89ab", rune(id[19])) {
		t.Fatalf("not a v4 UUID: %q", id)
	}
}

func TestParseSignInRedirect(t *testing.T) {
	tests := []struct {
		url      string
		ok       bool
		code     string
		canceled bool
		hasErr   bool
	}{
		{url: "https://login.live.com/oauth20_desktop.srf?code=M.C123&lc=1033", ok: true, code: "M.C123"},
		{url: "https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=The+user+has+denied+access", ok: true, canceled: true},
		{url: "https://login.live.com/oauth20_desktop.srf?error=server_error&error_description=Oops", ok: true, hasErr: true},
		{url: "https://login.live.com/oauth20_desktop.srf", ok: false},
		{url: "https://login.live.com/oauth20_authorize.srf?code=nope", ok: false},
		{url: "https://evil.example/oauth20_desktop.srf?code=nope", ok: false},
		{url: "http://login.live.com/oauth20_desktop.srf?code=nope", ok: false},
	}
	for _, tt := range tests {
		r, ok := parseSignInRedirect(tt.url)
		if ok != tt.ok || r.code != tt.code || r.canceled != tt.canceled || (r.err != nil) != tt.hasErr {
			t.Errorf("%s: got ok=%v %+v", tt.url, ok, r)
		}
	}
}

func TestIsLoginLiveURL(t *testing.T) {
	for raw, want := range map[string]bool{
		"https://login.live.com/oauth20_desktop.srf?code=x": true,
		"https://login.live.com":                            true,
		"https://login.live.com.evil.example/":              false,
		"http://login.live.com/":                            false,
		"":                                                  false,
	} {
		if got := isLoginLiveURL(raw); got != want {
			t.Errorf("isLoginLiveURL(%q) = %v, want %v", raw, got, want)
		}
	}
}

func TestAuthErrorMessages(t *testing.T) {
	err := &AuthError{Step: stepXsts, Status: 401, Body: `{"XErr":2148916233}`}
	if !strings.Contains(err.Error(), "Xbox profile") {
		t.Errorf("XErr 2148916233: got %q", err.Error())
	}
	err = &AuthError{Step: stepProfile, Status: 404, Body: `{}`}
	if !strings.Contains(err.Error(), "doesn't own Minecraft") {
		t.Errorf("profile 404: got %q", err.Error())
	}
}

func TestSignedPostHeaders(t *testing.T) {
	var got http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		w.Header().Set("Date", time.Now().Add(2*time.Hour).UTC().Format(http.TimeFormat))
		w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	key, err := newDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	c := newXboxClient()
	if _, _, err := c.signedPost(t.Context(), stepDeviceToken, srv.URL+"/device/authenticate", map[string]string{"a": "b"}, key); err != nil {
		t.Fatal(err)
	}
	if got.Get("Signature") == "" || got.Get("x-xbl-contract-version") != "1" || !strings.HasPrefix(got.Get("Content-Type"), "application/json") {
		t.Fatalf("missing signed-request headers: %v", got)
	}
	// The client should now follow the server's clock (2h ahead).
	if d := c.now().Sub(time.Now()); d < 119*time.Minute || d > 121*time.Minute {
		t.Fatalf("clock skew not applied: %v", d)
	}
}
