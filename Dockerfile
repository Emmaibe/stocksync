# Jenkins has already produced the fat jar, so this image only packages it.
# Matches the ASCCAAU/CandorAPI pattern: no rebuild inside Docker, which keeps
# the image build to a few seconds and means the artifact that was tested is
# exactly the artifact that ships.
FROM eclipse-temurin:25-jre-alpine

# Run as a non-root user. Nothing here needs root, and a container breakout is a
# great deal less interesting from an unprivileged account.
#
# The UID is explicit, and USER names it numerically. Kubernetes' runAsNonRoot
# check can only reason about UIDs: given a *name* it cannot tell whether the
# user is root, so it refuses to start the container with
# CreateContainerConfigError rather than risk it.
RUN addgroup -g 10001 -S stocksync && adduser -u 10001 -S stocksync -G stocksync

WORKDIR /app
COPY --chown=stocksync:stocksync target/*.jar app.jar
USER 10001

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM then sizes the heap from the container's
# memory limit, so changing the Kubernetes limit is enough and the two cannot
# drift apart. 75% leaves room for metaspace, threads and native buffers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Exit on OOM rather than limping: Kubernetes restarting a dead pod is better
# than traffic reaching one that cannot allocate.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
