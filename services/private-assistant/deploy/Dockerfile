FROM postgres:17-bookworm@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994
ARG AGE_COMMIT=e1467f12e0b1d15dd35d3ab93f057a7112d425b8
ARG PGVECTOR_COMMIT=778dacf20c07caf904557a88705142631818d8cb
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
    git build-essential postgresql-server-dev-17 flex bison ca-certificates \
 && git clone https://github.com/pgvector/pgvector.git /tmp/pgvector \
 && cd /tmp/pgvector && git checkout "$PGVECTOR_COMMIT" && make && make install \
 && git clone https://github.com/apache/age.git /tmp/age \
 && cd /tmp/age && git checkout "$AGE_COMMIT" && make && make install \
 && rm -rf /tmp/age /tmp/pgvector /var/lib/apt/lists/*
CMD ["postgres", "-c", "shared_preload_libraries=age", "-c", "max_connections=40", "-c", "shared_buffers=128MB"]
