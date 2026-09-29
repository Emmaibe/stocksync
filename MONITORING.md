# Monitoring

The principle behind all of this: **nothing running on the server can be trusted
to tell you the server is down.** So the alerting lives outside it, and the
application's job is only to expose the truth clearly when asked.

Three layers, in order of how much they are worth.

## 1. Is the API up? (external, do this first)

An outside service requests a URL every few minutes and alerts you when it
stops answering. This is the one that matters, and it takes about five minutes
to set up. Use UptimeRobot or Better Stack; the free tier of either is enough.

Monitor this URL:

```
https://api.stocksync.com.ng/actuator/health/readiness
```

Settings that matter:

- **Check every 3 minutes.** Any faster and you pay for noise.
- **Alert after 2 consecutive failures**, not one. A single failed check is
  usually the monitoring service's own network, and a monitor that cries wolf
  gets muted, which is worse than having none.
- **Expect HTTP 200.** The body is `{"status":"UP"}`.
- **Turn on the certificate expiry warning.** cert-manager renews
  automatically, but a renewal can fail quietly, and an expired certificate
  looks exactly like an outage to every client.

Why the readiness endpoint and not the home page: it returns 503 when the pod
cannot reach the database. A plain page might still answer 200 while the
application is unable to do anything useful.

## 2. Did last night's backup happen? (external, dead man's switch)

An alert that fires when nothing happens. Create a cron monitor at
healthchecks.io (free), set the expected period to 1 day with a few hours of
grace, and copy the ping URL it gives you. Then add it to the cluster secret:

```bash
kubectl patch secret stocksync-secrets -n stocksync \
  --type merge \
  -p "{\"stringData\":{\"BACKUP_PING_URL\":\"https://hc-ping.com/YOUR-UUID\"}}"
```

The backup job pings that URL as its very last step, only after the dump has
been verified. So the logic is inverted on purpose: **you get an alert when the
ping does not arrive.** A crashed job, a full disk, a deleted CronJob, a node
that never came back from a reboot — all of them are silent failures that this
catches, and none of them could have sent you an email themselves.

Without this, a backup that quietly stops running looks identical to one that
runs perfectly, until the day you need it.

## 3. Is anything rotting quietly? (the application's own view)

Some failures do not stop the API and will never trip an uptime check. The
biggest one here is email. The outbox is deliberately tolerant: SMTP can be
down for an hour and not a single request fails. That is correct behaviour, and
it also means a wrong SMTP password looks completely healthy from outside while
every invitation and password reset silently never arrives.

So there is a health group for operational checks. It needs a token, because its
body names the database and the mail server:

```bash
TOKEN=<an access token>
curl -sS https://api.stocksync.com.ng/actuator/health/ops \
  -H "Authorization: Bearer $TOKEN"
```

It reports three components:

| Component | What it tells you |
|---|---|
| `db` | The connection pool can reach PostgreSQL |
| `mail` | The SMTP server accepts a connection, using the real credentials |
| `outbox` | Queued mail is actually draining |

The `outbox` check goes DOWN if any message has been abandoned after its attempt
limit, or if the oldest waiting message is older than 15 minutes. The dispatcher
runs every 30 seconds, so anything waiting that long is not merely queued.

**These checks are not part of the readiness probe, and must not become part of
it.** A mail backlog would then take every pod out of the load balancer and turn
a mail problem into a full outage. `DeploymentReadinessIT` has a test that fails
if the two ever get mixed up.

### Numbers over time

The same figures are published as metrics, so they can be charted rather than
only sampled:

```bash
curl -sS https://api.stocksync.com.ng/actuator/metrics/stocksync.email.outbox.failed \
  -H "Authorization: Bearer $TOKEN"
```

Also available: `stocksync.email.outbox.pending` and
`stocksync.email.outbox.oldest.pending.minutes`, alongside everything Spring
Boot publishes by default — JVM memory, connection pool usage, HTTP request
timings per endpoint and status.

A health check tells you it is broken now. A metric tells you when it started,
which is the question you actually ask during an incident. Nothing scrapes these
yet; adding `micrometer-registry-prometheus` and a Grafana instance later needs
no application change, and is not worth running on one small VPS today.

## What to check by hand after a deploy

```bash
# Both pods healthy, and how many times they have restarted
kubectl get pods -n stocksync

# Which transport is wired. If this says "logging only", no mail is being sent.
kubectl logs -n stocksync -l app=stocksync | grep "Email delivery"

# Last night's backup
kubectl get job -n stocksync
```

A restart count that keeps climbing is the signal worth reacting to. It means
pods are being killed and replaced, usually by the liveness probe or by running
out of memory, and the API can look fine between restarts.

## Still missing

- **Log aggregation.** `kubectl logs` only shows the current pod. When a pod is
  replaced, its logs are gone, which is inconvenient precisely when a crash
  needs explaining.
- **Alerting on the ops group.** Nothing polls it automatically yet. The
  straightforward version is an uptime monitor with the auth header attached,
  once tokens live longer than 15 minutes or a dedicated monitoring account
  exists.
