# Email delivery

Two invitations and a password reset are the only mail this system sends, and
all three carry a credential that exists nowhere else. If they do not arrive,
nobody can join a workspace and nobody can recover an account. So this is worth
getting right once.

## Why Gmail could never have worked

The configuration used to point at `smtp.gmail.com` while sending as
`no-reply@stocksync.com.ng`. Gmail is not authorised to send for that domain, so
receiving servers either reject the message or file it as spam.

The dangerous part is how that looks from inside: SMTP accepts the message,
the outbox marks it SENT, and every health check stays green. Delivery failures
happen after the handover, at the receiving end, where this system never sees
them. "Sent" means handed over, never delivered.

## Setting it up

### 1. Resend account and domain

Sign up at resend.com, add `stocksync.com.ng` as a domain, and it will show a
set of DNS records to publish. They are generated per account, so copy the exact
values it gives you rather than anything written here.

Typically three: an `MX` and a `TXT` (SPF) on a sending subdomain such as
`send.stocksync.com.ng`, and a long `TXT` for DKIM on something like
`resend._domainkey`.

### 2. Publish them in Vercel DNS

Vercel holds the zone for `stocksync.com.ng`. In the project's Domains section,
open the DNS records for the domain and add each one. Two details that cost
people an afternoon:

- **Enter the name as the subdomain part only.** Vercel appends the domain, so
  `resend._domainkey` becomes `resend._domainkey.stocksync.com.ng`. Pasting the
  full name gives you `resend._domainkey.stocksync.com.ng.stocksync.com.ng`.
- **Do not re-wrap the values in quotes.** Vercel adds them for `TXT` records.

Then press Verify in Resend. It is usually a few minutes, occasionally an hour.

### 3. Add DMARC yourself

Resend does not ask for this and it is worth having. Add a `TXT` record named
`_dmarc` with:

```
v=DMARC1; p=none; rua=mailto:you@yourrealaddress.com
```

`p=none` asks receiving servers to report what they saw without changing how
they treat your mail — the safe starting point. The reports tell you whether
anything is sending as your domain, and once you are confident, `p=quarantine`
and later `p=reject` stop others from spoofing it.

### 4. Credentials into the cluster

The username is the literal word `resend` for every account. The password is an
API key from Resend's dashboard.

```bash
kubectl create secret generic stocksync-secrets -n stocksync \
  --from-literal=MAIL_USERNAME='resend' \
  --from-literal=MAIL_PASSWORD='re_...' \
  --dry-run=client -o yaml | kubectl apply -f -
```

That form merges rather than replaces, so `JWT_SECRET` and `DB_PASSWORD` survive.
Replacing the secret outright would sign every user out and stop the pods
starting.

Then restart so the pods pick it up:

```bash
kubectl rollout restart deployment/stocksync -n stocksync
```

## Checking it actually works

Three things, in order, because each one rules out a different failure.

**The transport is wired.** The application says so at startup:

```bash
kubectl logs -n stocksync -l app=stocksync | grep "Email delivery"
```

"logging only" means `SPRING_MAIL_HOST` is unset and nothing is being sent.

**The queue is draining.** With a token:

```bash
curl -sS https://api.stocksync.com.ng/actuator/health/ops \
  -H "Authorization: Bearer $TOKEN"
```

`outbox` reports DOWN if any message was abandoned, or if the oldest has waited
more than 15 minutes. A wrong API key looks exactly like this.

**It arrived.** Send a real invitation to an address you can open, and check
Resend's dashboard, which shows delivered, bounced and complained per message —
the part SMTP cannot tell you. Check the spam folder too: landing in spam is a
DNS problem, not a code one, and the fix is back in step 2.

## What still bites later

- **Volume.** The free tier is 3,000 a month and 100 a day. The outbox retries
  with a doubling backoff, so a rate limit delays mail rather than losing it,
  but nothing alerts you when you hit the ceiling.
- **Bounces are invisible to this system.** A message accepted by Resend and
  then bounced is SENT here forever. Resend can post a webhook for that; wiring
  it into `email_message` would make the outbox honest about delivery rather
  than about handover.
- **A wrong `APP_BASE_URL`** sends perfectly deliverable mail containing links
  to the wrong place. It is in the configmap and must match the deployed web
  app.
