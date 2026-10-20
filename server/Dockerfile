# syntax=docker/dockerfile:1
# Static musl binary on an empty base image (~5 MB total).
# Builds natively for whichever platform runs it (linux/amd64 or linux/arm64);
# see README.md for multi-arch builds with buildx.

FROM rust:1.96-alpine3.22 AS build
RUN apk add --no-cache musl-dev
WORKDIR /src

# Cache dependencies separately from our own code.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs \
 && cargo build --release --locked \
 && rm -rf src target/release/deps/livetv_server* target/release/livetv-server*

COPY src ./src
RUN cargo build --release --locked \
 && mkdir -p /out/data \
 && cp target/release/livetv-server /out/livetv-server

FROM scratch
COPY --from=build /out/livetv-server /livetv-server
# Owned by nobody so a fresh named volume mounted here is writable.
COPY --from=build --chown=65534:65534 /out/data /data
USER 65534:65534
ENV PORT=5001 DATA_DIR=/data RUST_LOG=info
EXPOSE 5001
VOLUME ["/data"]
HEALTHCHECK --interval=60s --timeout=10s --start-period=20s --retries=3 CMD ["/livetv-server", "healthcheck"]
ENTRYPOINT ["/livetv-server"]
