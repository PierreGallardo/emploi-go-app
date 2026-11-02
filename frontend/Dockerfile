FROM node:24.19.0-trixie-slim@sha256:ab3eebe934147fee049b5eb83c570f68c849a13c930bdfa482de99fcdfa3b3de AS build
WORKDIR /build
COPY --link package.json package-lock.json .npmrc ./
COPY --link frontend/package.json frontend/
RUN --mount=type=cache,target=/root/.npm \
    npm ci --workspace frontend --ignore-scripts --no-audit --no-fund
COPY --link frontend/ frontend/
# The prerender reaches the Vite preview server at 127.0.0.1, while a build container resolves localhost to ::1 first.
RUN NODE_OPTIONS=--dns-result-order=ipv4first npm run build --workspace frontend

FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f AS caddy
# The binary carries cap_net_bind_service: without that capability, which port 8080 does not need, it would not start.
RUN apk add --no-cache libcap-utils \
    && setcap -r /usr/bin/caddy

FROM gcr.io/distroless/static-debian13:nonroot@sha256:18f4cc26c9ffa7f20a359298be7deadac5c607c8e03a02b8119f1a5fc2f82c99
COPY --link --from=caddy /usr/bin/caddy /usr/bin/caddy
COPY --link frontend/Caddyfile /etc/caddy/Caddyfile
COPY --link --from=build /build/frontend/build/csp.caddyfile /etc/caddy/csp.caddyfile
COPY --link --from=build /build/frontend/build/client/ /srv/
# Caddy writes its instance identifier and storage locks at start: in /tmp, a read-only root file system only needs a tmpfs there.
ENV XDG_CONFIG_HOME=/tmp XDG_DATA_HOME=/tmp
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/bin/caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
