# Backup and restore

The database is the only part of this system that cannot be rebuilt from the
repository. Everything else — image, manifests, secrets template — is code.

## What runs

`k8s/backup-cronjob.yaml` dumps the database nightly at 02:15 UTC into the
`stocksync-backups` volume, verifies the archive by reading its table of
contents back, then prunes dumps older than 14 days. It prunes only after the
verification passes, so a run that fails never deletes the last good copy.

Check on it:

```bash
kubectl get cronjob,job -n stocksync
kubectl logs -n stocksync -l job-name="$(kubectl get job -n stocksync \
  -o jsonpath='{.items[-1:].metadata.name}')"
```

Run one on demand — do this now rather than waiting for 02:15:

```bash
kubectl create job -n stocksync --from=cronjob/stocksync-backup backup-manual-1
kubectl logs -n stocksync -f job/backup-manual-1
```

## The gap you still have

That volume is a directory on the same VPS as the database. It covers a bad
migration, a wrong `DELETE`, a dropped table. It does **not** cover losing the
server, which is the scenario that ends companies.

Until an off-site copy exists, pull the latest dump down by hand after anything
important happens:

```bash
ssh root@207.180.243.191 \
  "kubectl exec -n stocksync deploy/stocksync -- true 2>/dev/null; \
   ls -t /var/lib/rancher/k3s/storage/*stocksync-backups*/stocksync-*.dump | head -1" \
  | xargs -I{} scp root@207.180.243.191:{} ~/stocksync-backups/
```

The real fix is an object store (Backblaze B2 is about $6/TB/month, S3-compatible).
Once a bucket exists, add its credentials to `stocksync-secrets` and append an
upload step to the CronJob script after verification:

```sh
rclone copy "$OUT" "b2:$B2_BUCKET/stocksync/" --no-traverse
```

That turns a same-host snapshot into an actual backup. It is the single highest-
value thing left on the infrastructure side.

## Restoring

`pg_dump -Fc` archives are read with `pg_restore`, never `psql`.

### Into a scratch database, to check the dump is real

Do this quarterly. A backup nobody has restored is a hypothesis.

```bash
ssh root@207.180.243.191
DUMP=$(ls -t /var/lib/rancher/k3s/storage/*stocksync-backups*/stocksync-*.dump | head -1)

sudo -u postgres createdb stocksync_restoretest
sudo -u postgres pg_restore -d stocksync_restoretest --no-owner --no-privileges "$DUMP"

# Does the ledger agree with the projection it is supposed to produce?
sudo -u postgres psql -d stocksync_restoretest -c "
  SELECT count(*) AS movements FROM stock_movement;
  SELECT count(*) AS slots, sum(quantity_on_hand) AS on_hand FROM stock_level;
  SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"

sudo -u postgres dropdb stocksync_restoretest
```

Three things must hold: `pg_restore` exits 0, the Flyway history is present and
`success = true` on the newest row, and the movement count is roughly what you
expect. If Flyway history is missing, the application will try to re-run V1
against a populated database and fail at startup.

### Over the live database, in a real disaster

Order matters. Restoring into a database the application is writing to produces
a corrupted mix of both.

```bash
# 1. Stop all writers first. maxUnavailable: 0 means scaling to zero is the only
#    way to be sure nothing is holding a connection.
kubectl scale deploy/stocksync -n stocksync --replicas=0

# 2. Keep the damaged database rather than dropping it — it is evidence, and it
#    may hold rows the dump predates.
sudo -u postgres psql -c "ALTER DATABASE stocksync RENAME TO stocksync_damaged_$(date -u +%Y%m%d);"
sudo -u postgres createdb -O stocksync stocksync

# 3. Restore.
sudo -u postgres pg_restore -d stocksync --no-owner --no-privileges "$DUMP"
sudo -u postgres psql -d stocksync -c "GRANT ALL ON ALL TABLES IN SCHEMA public TO stocksync;
  GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO stocksync;"

# 4. Bring the application back and let Flyway confirm it has nothing to do.
kubectl scale deploy/stocksync -n stocksync --replicas=2
kubectl rollout status deploy/stocksync -n stocksync
curl -sS https://api.stocksync.com.ng/actuator/health/readiness
```

Then repair any stock levels whose projection drifted, per tenant and slot, with
the rebuild endpoint — `stock_level` is derived from `stock_movement`, so the
ledger is the truth and the projection can always be recomputed from it.

## What this does not protect

- **The JWT secret.** Rotating or losing it signs everyone out at once. It lives
  only in `stocksync-secrets` on the cluster; keep a copy somewhere safe.
- **Point-in-time recovery.** A nightly dump means up to 24 hours of accepted
  movements can be lost. Closing that needs WAL archiving, which is worth doing
  once real customers are recording stock.
