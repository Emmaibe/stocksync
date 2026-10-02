# Rotating the credentials that reached git history

Three secrets were committed at some point and are still in the repository's
history. Removing them from the working tree does not remove them from history:
anyone with a clone, now or later, can read them. They have to be replaced at
the source.

This is about an hour, start to finish. Do them in this order — the JWT key is
last because it signs everyone out.

| What | Where it lives now | Why it matters |
|---|---|---|
| ASCCAAU Gmail app password | Google account `asccaa.uniben@gmail.com` | Sends mail as that mailbox |
| `stocsync@gmail.com` app password | Google account `stocsync@gmail.com` | Same, for the old StockSync mailbox |
| `JWT_SECRET` | `stocksync-secrets` in the cluster | Signs every access token. Anyone holding it can mint a token for any tenant |

---

## 1. The two Gmail app passwords

An app password is a full password for that mailbox as far as SMTP is
concerned. Neither is used by StockSync any more — mail goes through Resend —
so these are revoked, not replaced.

1. Sign in to `asccaa.uniben@gmail.com`.
2. Go to the Google account security page, then **App passwords**.
3. Delete the entry that was used for SMTP. If you cannot tell which, delete
   them all and recreate only the one ASCCAAU still needs.
4. Repeat for `stocsync@gmail.com`.

If ASCCAAU is still sending mail through one of these, create a fresh app
password there and update that application's own configuration — do not reuse
the old value.

## 2. The Resend API key

Not in git history, but worth checking while you are here. In the Resend
dashboard, the key StockSync uses should have **sending access only** and be
scoped to `stocksync.com.ng`. A full-access key can read your domains and
create more keys.

If it is wider than that, create a correctly scoped one and patch it in:

```sh
kubectl -n stocksync patch secret stocksync-secrets \
  --type merge -p '{"stringData":{"MAIL_PASSWORD":"re_the_new_key"}}'
kubectl -n stocksync rollout restart deployment/stocksync
```

Delete the old key in Resend only after the new pods are serving.

## 3. The JWT signing key

This one signs everyone out, so do it when that is acceptable. Today, while you
are the only user, is the cheapest it will ever be.

```sh
# Keep a copy somewhere outside the cluster FIRST. Losing this is not
# recoverable; it only means everyone signs in again, but you want to know what
# the running value is.
kubectl -n stocksync get secret stocksync-secrets \
  -o jsonpath='{.data.JWT_SECRET}' | base64 -d; echo

NEW=$(openssl rand -base64 48)
kubectl -n stocksync patch secret stocksync-secrets \
  --type merge -p "{\"stringData\":{\"JWT_SECRET\":\"$NEW\"}}"
kubectl -n stocksync rollout restart deployment/stocksync
kubectl -n stocksync rollout status deployment/stocksync
```

Then sign in again on `stocksync.com.ng`. Everyone else is signed out and signs
in normally; nobody needs to reset a password.

**`patch`, never `apply`.** `kubectl apply` on a partial secret file wipes the
keys the file does not mention, which would take `DB_PASSWORD` with it and stop
the pods from starting.

## 4. Store the new key off the cluster

If the cluster is lost, the secret is lost with it. Put the current
`JWT_SECRET` and `DB_PASSWORD` in a password manager entry named for the
environment. That is the only copy that survives a rebuilt node.

## 5. Delete the smoke-test tenant

The production verification created a tenant called `smoke-test` with real
rows in it. It is harmless but it is not a customer.

```sh
kubectl -n stocksync exec -it deploy/postgres -- \
  psql -U stocksync -d stocksync -c \
  "select id, slug, name from tenant where slug like 'smoke-test%';"
```

Then delete by id, children first, or leave it and rename it to something
obviously internal. Deleting a tenant has no endpoint yet, which is item 13 on
the gaps list.

---

## What this does not fix

The old values stay in git history. Rotating makes them worthless, which is the
point — rewriting history on a repository you have already pushed is a bigger
operation and buys nothing once the secrets are dead.

To stop the next one: `.env.example` holds placeholders only, real values live
in the cluster secret, and nothing with a real credential in it belongs in a
file that git tracks.
