FROM postgres:18.6

# apt-archive.postgresql.org keeps every PGDG version: the pinned PostGIS stays installable once the main repository drops it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && echo 'deb [ signed-by=/usr/local/share/keyrings/postgres.gpg.asc ] https://apt-archive.postgresql.org/pub/repos/apt trixie-pgdg-archive main' \
        > /etc/apt/sources.list.d/pgdg-archive.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-18-postgis-3=3.6.4+dfsg-2.pgdg13+1 \
        postgresql-18-postgis-3-scripts=3.6.4+dfsg-2.pgdg13+1 \
    && rm -rf /var/lib/apt/lists/*
