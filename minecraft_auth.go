//  1. device token   – device.auth.xboxlive.com (proves this install's key)
//  2. SISU auth      – sisu.xboxlive.com/authenticate → Microsoft login URL
//  3. user signs in  – window lands on oauth20_desktop.srf?code=…
//  4. OAuth token    – login.live.com/oauth20_token.srf (code + PKCE verifier)
//  5. SISU authorize – user + title tokens
//  6. XSTS           – token for api.minecraftservices.com
//  7. Minecraft token, 8. entitlements, 9. profile (UUID + username)

package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	microsoftClientID = "00000000402b5328"
	authReplyURL      = "https://login.live.com/oauth20_desktop.srf"
	requestedScope    = "service::user.auth.xboxlive.com::MBI_SSL"
	minecraftTitleID  = "1794566092"
	userAgent         = "Mauve Launcher"

	deviceAuthURL    = "https://device.auth.xboxlive.com/device/authenticate"
	sisuAuthURL      = "https://sisu.xboxlive.com/authenticate"
	sisuAuthorizeURL = "https://sisu.xboxlive.com/authorize"
	xstsAuthorizeURL = "https://xsts.auth.xboxlive.com/xsts/authorize"
	oauthTokenURL    = "https://login.live.com/oauth20_token.srf"
	mcLoginURL       = "https://api.minecraftservices.com/launcher/login"
	mcEntitlementURL = "https://api.minecraftservices.com/entitlements/license"
	mcProfileURL     = "https://api.minecraftservices.com/minecraft/profile"

	// seconds between 1601-01-01 (Windows FILETIME epoch) and 1970-01-01
	filetimeEpochOffset = 11644473600
)

// Errors

type authStep string

const (
	stepDeviceToken    authStep = "device token"
	stepSisuAuth       authStep = "Xbox sign-in setup"
	stepOAuthToken     authStep = "Microsoft token"
	stepSisuAuthorize  authStep = "Xbox authorization"
	stepXsts           authStep = "Xbox Live authorization"
	stepMinecraftToken authStep = "Minecraft token"
	stepEntitlements   authStep = "Minecraft entitlements"
	stepProfile        authStep = "Minecraft profile"
)

// failed request during sign-in
type AuthError struct {
	Step   authStep
	Status int
	Body   string
	Err    error
}

func (e *AuthError) Error() string {
	if msg := e.friendlyMessage(); msg != "" {
		return msg
	}
	if e.Err != nil {
		return fmt.Sprintf("Sign-in failed during %s: %v", e.Step, e.Err)
	}
	return fmt.Sprintf("Sign-in failed during %s (HTTP %d): %s", e.Step, e.Status, e.Body)
}

func (e *AuthError) Unwrap() error {
	return e.Err
}

func (e *AuthError) friendlyMessage() string {
	var xerr struct {
		XErr uint64 `json:"XErr"`
	}
	if json.Unmarshal([]byte(e.Body), &xerr) == nil && xerr.XErr != 0 {
		switch xerr.XErr {
		case 2148916222:
			return "This account needs age verification on xbox.com before it can sign in."
		case 2148916227:
			return "This Xbox account is banned."
		case 2148916229:
			return "This account is restricted (for example, by parental controls) from signing in."
		case 2148916233:
			return "This Microsoft account doesn't have an Xbox profile yet. Sign in at xbox.com once to create one, then try again."
		case 2148916234:
			return "This account hasn't accepted the Xbox terms of use. Sign in at xbox.com to accept them, then try again."
		case 2148916235:
			return "Xbox Live isn't available in this account's region."
		case 2148916236, 2148916237:
			return "This account needs adult verification on xbox.com before it can sign in."
		case 2148916238:
			return "This account belongs to someone under 18 and must be added to a Microsoft family group by an adult."
		}
	}
	switch {
	case e.Step == stepXsts && e.Err == nil:
		return "Xbox Live rejected this account."
	case e.Step == stepSisuAuth && e.Err == nil:
		return "Xbox Live rejected the sign-in request. Make sure your computer's clock is set correctly."
	case e.Step == stepMinecraftToken && e.Status == http.StatusTooManyRequests:
		return "Too many sign-in attempts. Wait a few minutes and try again."
	case e.Step == stepMinecraftToken && e.Status >= 500:
		return "Minecraft's sign-in service is unavailable right now. Try again later."
	case e.Step == stepProfile && e.Status == http.StatusNotFound:
		return "This Microsoft account doesn't own Minecraft: Java Edition."
	}
	return ""
}

// Device key

// deviceKey is this install's P-256 key, used to sign Xbox Live requests
type deviceKey struct {
	ID   string // UUID
	Priv *ecdsa.PrivateKey
	X, Y string // base64url public key coordinates
}

func newDeviceKey() (*deviceKey, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	id, err := newUUID()
	if err != nil {
		return nil, err
	}
	return deviceKeyFrom(id, priv)
}

func deviceKeyFrom(id string, priv *ecdsa.PrivateKey) (*deviceKey, error) {
	// Uncompressed point: 0x04 ‖ X(32) ‖ Y(32)
	point, err := priv.PublicKey.Bytes()
	if err != nil {
		return nil, err
	}
	if len(point) != 65 || point[0] != 4 {
		return nil, errors.New("unexpected P-256 public key encoding")
	}
	enc := base64.RawURLEncoding
	return &deviceKey{
		ID:   id,
		Priv: priv,
		X:    enc.EncodeToString(point[1:33]),
		Y:    enc.EncodeToString(point[33:65]),
	}, nil
}

func (k *deviceKey) privatePEM() (string, error) {
	der, err := x509.MarshalPKCS8PrivateKey(k.Priv)
	if err != nil {
		return "", err
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})), nil
}

func parseDeviceKey(id, privatePEM string) (*deviceKey, error) {
	block, _ := pem.Decode([]byte(privatePEM))
	if block == nil {
		return nil, errors.New("device key: invalid PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	priv, ok := parsed.(*ecdsa.PrivateKey)
	if !ok || priv.Curve != elliptic.P256() {
		return nil, errors.New("device key: not a P-256 key")
	}
	return deviceKeyFrom(id, priv)
}

func (k *deviceKey) proofKey() map[string]string {
	return map[string]string{
		"kty": "EC",
		"x":   k.X,
		"y":   k.Y,
		"crv": "P-256",
		"alg": "ES256",
		"use": "sig",
	}
}

// Response types

// is the token shape returned by device, SISU and XSTS endpoints
type xboxToken struct {
	IssueInstant  time.Time `json:"IssueInstant"`
	NotAfter      time.Time `json:"NotAfter"`
	Token         string    `json:"Token"`
	DisplayClaims struct {
		Xui []struct {
			Uhs string `json:"uhs"`
		} `json:"xui"`
	} `json:"DisplayClaims"`
}

type sisuAuthorizeResponse struct {
	TitleToken xboxToken `json:"TitleToken"`
	UserToken  xboxToken `json:"UserToken"`
}

type oauthTokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
}

type minecraftTokenResponse struct {
	AccessToken string `json:"access_token"`
}

type minecraftProfile struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type loginFlow struct {
	Verifier  string
	SessionID string
	AuthURL   string
}

// Client

// makes the HTTP calls and tracks the offset between our clock and Microsoft's so signed request timestamps are accepted even when the local clock is a little off
type xboxClient struct {
	http *http.Client

	mu   sync.Mutex
	skew time.Duration
}

func newXboxClient() *xboxClient {
	return &xboxClient{http: &http.Client{Timeout: 30 * time.Second}}
}

func (c *xboxClient) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return time.Now().Add(c.skew)
}

func (c *xboxClient) noteServerDate(h http.Header) {
	t, err := http.ParseTime(h.Get("Date"))
	if err != nil {
		return
	}
	c.mu.Lock()
	c.skew = time.Until(t)
	c.mu.Unlock()
}

func (c *xboxClient) do(ctx context.Context, step authStep, build func() (*http.Request, error)) (*http.Response, []byte, error) {
	const attempts = 5
	var lastErr error
	for i := 0; i < attempts; i++ {
		req, err := build()
		if err != nil {
			return nil, nil, &AuthError{Step: step, Err: err}
		}
		req = req.WithContext(ctx)
		req.Header.Set("User-Agent", userAgent)
		resp, err := c.http.Do(req)
		if err == nil {
			body, readErr := io.ReadAll(resp.Body)
			resp.Body.Close()
			if readErr != nil {
				return nil, nil, &AuthError{Step: step, Err: readErr}
			}
			c.noteServerDate(resp.Header)
			return resp, body, nil
		}
		lastErr = err
		if ctx.Err() != nil || !isRetryable(err) {
			break
		}
		select {
		case <-time.After(250 * time.Millisecond):
		case <-ctx.Done():
			return nil, nil, ctx.Err()
		}
	}
	return nil, nil, &AuthError{Step: step, Err: lastErr}
}

func isRetryable(err error) bool {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return true
	}
	var opErr *net.OpError
	return errors.As(err, &opErr) && opErr.Op == "dial"
}

func decode(step authStep, resp *http.Response, body []byte, out any) error {
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return &AuthError{Step: step, Status: resp.StatusCode, Body: string(body)}
	}
	if err := json.Unmarshal(body, out); err != nil {
		return &AuthError{Step: step, Status: resp.StatusCode, Body: string(body), Err: err}
	}
	return nil
}

// Signed payload: u32be(1) 0 u64be(t) 0 method 0 path 0 authorization 0 body 0,
// where t is a Windows FILETIME. Header: base64(i32be(1) ‖ u64be(t) ‖ r ‖ s).
func signRequest(key *deviceKey, at time.Time, method, path, authorization string, body []byte) (string, error) {
	t := uint64(at.Unix()+filetimeEpochOffset) * 10_000_000

	var payload bytes.Buffer
	payload.Write(binary.BigEndian.AppendUint32(nil, 1))
	payload.WriteByte(0)
	payload.Write(binary.BigEndian.AppendUint64(nil, t))
	payload.WriteByte(0)
	payload.WriteString(method)
	payload.WriteByte(0)
	payload.WriteString(path)
	payload.WriteByte(0)
	payload.WriteString(authorization)
	payload.WriteByte(0)
	payload.Write(body)
	payload.WriteByte(0)

	digest := sha256.Sum256(payload.Bytes())
	r, s, err := ecdsa.Sign(rand.Reader, key.Priv, digest[:])
	if err != nil {
		return "", err
	}

	sig := make([]byte, 0, 4+8+64)
	sig = binary.BigEndian.AppendUint32(sig, 1)
	sig = binary.BigEndian.AppendUint64(sig, t)
	sig = append(sig, r.FillBytes(make([]byte, 32))...)
	sig = append(sig, s.FillBytes(make([]byte, 32))...)
	return base64.StdEncoding.EncodeToString(sig), nil
}

func (c *xboxClient) signedPost(ctx context.Context, step authStep, endpoint string, payload any, key *deviceKey) (*http.Response, []byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, nil, &AuthError{Step: step, Err: err}
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, nil, &AuthError{Step: step, Err: err}
	}
	return c.do(ctx, step, func() (*http.Request, error) {
		signature, err := signRequest(key, c.now(), http.MethodPost, u.Path, "", body)
		if err != nil {
			return nil, err
		}
		req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
		req.Header.Set("Accept", "application/json")
		req.Header.Set("Signature", signature)
		if endpoint != sisuAuthorizeURL {
			req.Header.Set("x-xbl-contract-version", "1")
		}
		return req, nil
	})
}

// Flow steps

// registers this installation's key with Xbox Live
func (c *xboxClient) deviceToken(ctx context.Context, key *deviceKey) (*xboxToken, error) {
	payload := map[string]any{
		"Properties": map[string]any{
			"AuthMethod": "ProofOfPossession",
			"Id":         "{" + strings.ToUpper(key.ID) + "}",
			"DeviceType": "Win32",
			"Version":    "10.16.0",
			"ProofKey":   key.proofKey(),
		},
		"RelyingParty": "http://auth.xboxlive.com",
		"TokenType":    "JWT",
	}
	resp, body, err := c.signedPost(ctx, stepDeviceToken, deviceAuthURL, payload, key)
	if err != nil {
		return nil, err
	}
	var tok xboxToken
	if err := decode(stepDeviceToken, resp, body, &tok); err != nil {
		return nil, err
	}
	return &tok, nil
}

// starts a sign-in and returns the Microsoft login URL
func (c *xboxClient) sisuAuthenticate(ctx context.Context, deviceTok string, key *deviceKey) (*loginFlow, error) {
	verifier, err := randomHex(64)
	if err != nil {
		return nil, err
	}
	state, err := randomHex(64)
	if err != nil {
		return nil, err
	}
	payload := map[string]any{
		"AppId":       microsoftClientID,
		"DeviceToken": deviceTok,
		"Offers":      []string{requestedScope},
		"Query": map[string]string{
			"code_challenge":        pkceChallenge(verifier),
			"code_challenge_method": "S256",
			"state":                 state,
			"prompt":                "select_account",
		},
		"RedirectUri": authReplyURL,
		"Sandbox":     "RETAIL",
		"TokenType":   "code",
		"TitleId":     minecraftTitleID,
	}
	resp, body, err := c.signedPost(ctx, stepSisuAuth, sisuAuthURL, payload, key)
	if err != nil {
		return nil, err
	}
	var out struct {
		MsaOauthRedirect string `json:"MsaOauthRedirect"`
	}
	if err := decode(stepSisuAuth, resp, body, &out); err != nil {
		return nil, err
	}
	sessionID := resp.Header.Get("X-SessionId")
	if sessionID == "" {
		return nil, &AuthError{Step: stepSisuAuth, Err: errors.New("no X-SessionId header in response")}
	}
	if out.MsaOauthRedirect == "" {
		return nil, &AuthError{Step: stepSisuAuth, Err: errors.New("no login URL in response")}
	}
	return &loginFlow{Verifier: verifier, SessionID: sessionID, AuthURL: out.MsaOauthRedirect}, nil
}

// exchanges the code from the login window for Microsoft tokens
func (c *xboxClient) oauthToken(ctx context.Context, code, verifier string) (*oauthTokenResponse, error) {
	form := url.Values{
		"client_id":     {microsoftClientID},
		"code":          {code},
		"code_verifier": {verifier},
		"grant_type":    {"authorization_code"},
		"redirect_uri":  {authReplyURL},
		"scope":         {requestedScope},
	}.Encode()
	resp, body, err := c.do(ctx, stepOAuthToken, func() (*http.Request, error) {
		req, err := http.NewRequest(http.MethodPost, oauthTokenURL, strings.NewReader(form))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Accept", "application/json")
		return req, nil
	})
	if err != nil {
		return nil, err
	}
	var tok oauthTokenResponse
	if err := decode(stepOAuthToken, resp, body, &tok); err != nil {
		return nil, err
	}
	return &tok, nil
}

// turns the Microsoft access token into Xbox user + title tokens
func (c *xboxClient) sisuAuthorize(ctx context.Context, sessionID, accessToken, deviceTok string, key *deviceKey) (*sisuAuthorizeResponse, error) {
	payload := map[string]any{
		"AccessToken":       "t=" + accessToken,
		"AppId":             microsoftClientID,
		"DeviceToken":       deviceTok,
		"ProofKey":          key.proofKey(),
		"Sandbox":           "RETAIL",
		"SessionId":         sessionID,
		"SiteName":          "user.auth.xboxlive.com",
		"RelyingParty":      "http://xboxlive.com",
		"UseModernGamertag": true,
	}
	resp, body, err := c.signedPost(ctx, stepSisuAuthorize, sisuAuthorizeURL, payload, key)
	if err != nil {
		return nil, err
	}
	var out sisuAuthorizeResponse
	if err := decode(stepSisuAuthorize, resp, body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// gets the XSTS token that Minecraft's services accept
func (c *xboxClient) xstsAuthorize(ctx context.Context, auth *sisuAuthorizeResponse, deviceTok string, key *deviceKey) (*xboxToken, error) {
	payload := map[string]any{
		"RelyingParty": "rp://api.minecraftservices.com/",
		"TokenType":    "JWT",
		"Properties": map[string]any{
			"SandboxId":   "RETAIL",
			"UserTokens":  []string{auth.UserToken.Token},
			"DeviceToken": deviceTok,
			"TitleToken":  auth.TitleToken.Token,
		},
	}
	resp, body, err := c.signedPost(ctx, stepXsts, xstsAuthorizeURL, payload, key)
	if err != nil {
		return nil, err
	}
	var tok xboxToken
	if err := decode(stepXsts, resp, body, &tok); err != nil {
		return nil, err
	}
	return &tok, nil
}

// logs in to Minecraft's services with the XSTS token
func (c *xboxClient) minecraftToken(ctx context.Context, xsts *xboxToken) (string, error) {
	if len(xsts.DisplayClaims.Xui) == 0 || xsts.DisplayClaims.Xui[0].Uhs == "" {
		return "", &AuthError{Step: stepXsts, Err: errors.New("no user hash in Xbox token")}
	}
	body, err := json.Marshal(map[string]string{
		"platform": "PC_LAUNCHER",
		"xtoken":   fmt.Sprintf("XBL3.0 x=%s;%s", xsts.DisplayClaims.Xui[0].Uhs, xsts.Token),
	})
	if err != nil {
		return "", err
	}
	resp, respBody, err := c.do(ctx, stepMinecraftToken, func() (*http.Request, error) {
		req, err := http.NewRequest(http.MethodPost, mcLoginURL, bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json")
		return req, nil
	})
	if err != nil {
		return "", err
	}
	var tok minecraftTokenResponse
	if err := decode(stepMinecraftToken, resp, respBody, &tok); err != nil {
		return "", err
	}
	if tok.AccessToken == "" {
		return "", &AuthError{Step: stepMinecraftToken, Status: resp.StatusCode, Body: string(respBody)}
	}
	return tok.AccessToken, nil
}

// makes an authenticated GET to Minecraft's services
func (c *xboxClient) bearerGet(ctx context.Context, step authStep, endpoint, token string, out any) error {
	resp, body, err := c.do(ctx, step, func() (*http.Request, error) {
		req, err := http.NewRequest(http.MethodGet, endpoint, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		return req, nil
	})
	if err != nil {
		return err
	}
	return decode(step, resp, body, out)
}

// checks if the account owns the game lol
func (c *xboxClient) minecraftEntitlements(ctx context.Context, token string) error {
	requestID, err := newUUID()
	if err != nil {
		return err
	}
	var out json.RawMessage
	return c.bearerGet(ctx, stepEntitlements, mcEntitlementURL+"?requestId="+requestID, token, &out)
}

// fetch the player's UUID and username
func (c *xboxClient) minecraftProfile(ctx context.Context, token string) (*minecraftProfile, error) {
	var p minecraftProfile
	if err := c.bearerGet(ctx, stepProfile, mcProfileURL, token, &p); err != nil {
		return nil, err
	}
	if p.ID == "" || p.Name == "" {
		return nil, &AuthError{Step: stepProfile, Status: http.StatusNotFound}
	}
	return &p, nil
}

// Helpers

func pkceChallenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func newUUID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b)
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32], nil
}
