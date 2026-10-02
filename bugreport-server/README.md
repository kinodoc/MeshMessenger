# MeshMessenger bug-report receiver (VPS)

This is a preparation scaffold; it is NOT deployed and the Android app is not yet pointed at it.

## Flow
1. App creates a ZIP and POSTs its bytes to `/api/bugreports` over HTTPS as `application/zip`.
2. Receiver validates size and ZIP signature, applies per-IP limits, stores the archive, and creates an Issue through GitHub's REST API.
3. Receiver returns JSON `{"ok":true,"issue_url":"...","issue_number":123}` only after GitHub confirms creation. Failures return a non-success status and `ok:false`.

## Security and deployment prerequisites
- Choose a DNS name and configure valid TLS before enabling app uploads. Do not assume an existing VPS hostname has HTTPS configured.
- Create a GitHub fine-grained token restricted to `kinodoc/MeshMessenger` with Issues: Read and write; store it only in `/etc/mesh-bugreport.env` (mode 0600), never in the APK or repository.
- Deploy behind an HTTPS reverse proxy. Keep port 8765 bound to loopback.
- Add expiry/retention for uploaded archives and serve them only through a controlled HTTPS route. The included Nginx fragment is only a starting point: public ZIP links need access control or short expiry because reports contain device diagnostics.
- Configure repository labels or remove the `bug` label from server.py if the label does not exist.
- Current minimal receiver uses in-memory rate limits (reset on restart) and should be hardened before public release: persistent rate limits, abuse controls, authentication/challenge, archive expiry, audit policy, monitoring and request timeouts.
- The server's success response does not mean the user has installed a new APK. The app integration must show success only when `ok=true` and an issue URL is returned.

## Files
- `server.py`: standard-library-only Python HTTP API.
- `mesh-bugreport.service`: systemd service template.
- `mesh-bugreport.env.example`: environment template, no real secrets.
- `nginx-location.conf`: reverse-proxy starting fragment.
