# MeshMessenger bug-report receiver

Preparation scaffold for the private VPS receiver. It is not deployed automatically.

## Flow
- The app creates a ZIP locally and uploads it to `https://194.87.186.159/api/bugreports` as `application/zip`.
- The receiver validates the ZIP, limits upload size and rate, fingerprints normalized diagnostic text, and uses SQLite to deduplicate across manual and future automatic submissions.
- Responses intentionally contain no GitHub issue URL: `status=sent` or `status=duplicate`.
- Archives remain private on the VPS and are not served over HTTP. GitHub issues contain only app version and a short fingerprint, not a public archive URL.

## Deployment safety
- Keep port 8765 bound to loopback and expose only the HTTPS Nginx route.
- Store the fine-grained GitHub token only in `/etc/mesh-bugreport.env`, mode 0600. Never put it in the APK or repository.
- Ensure `/var/lib/mesh-bugreports` is owned by `meshbugreport` and mode 0700.
- Review retention policy for private ZIPs and SQLite deduplication rows before production use.
- Check GitHub Issues write permission with a controlled test before enabling public app uploads.
- The app's manual button is integrated; automatic crash-triggered uploads remain intentionally disabled until consent and trigger rules are finalized.
