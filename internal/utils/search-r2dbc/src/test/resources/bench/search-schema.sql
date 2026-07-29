CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE search_things (
    thing_id       text COLLATE "C" PRIMARY KEY,
    namespace      text COLLATE "C" NOT NULL,
    revision       bigint NOT NULL,
    policy_id      text,
    policy_rev     bigint,
    referenced_policies jsonb,
    global_read    text[] NOT NULL DEFAULT '{}',
    thing          jsonb NOT NULL,
    policy_auth    jsonb,
    features_auth  jsonb,
    t_modified     timestamptz,
    delete_at      timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX st_namespace          ON search_things (namespace, thing_id);
CREATE INDEX st_global_read       ON search_things USING gin (global_read);
CREATE INDEX st_policy            ON search_things (policy_id, policy_rev);
CREATE INDEX st_referenced_pols   ON search_things USING gin (referenced_policies jsonb_path_ops);
CREATE INDEX st_delete_at         ON search_things (delete_at) WHERE delete_at IS NOT NULL;
CREATE INDEX st_modified          ON search_things (t_modified);

CREATE TABLE search_flat (
    thing_id   text COLLATE "C" NOT NULL REFERENCES search_things (thing_id) ON DELETE CASCADE,
    path       text COLLATE "C" NOT NULL,
    wpath      text COLLATE "C" NOT NULL,
    f_id       text,
    ord        integer NOT NULL DEFAULT 0,
    type_rank  smallint NOT NULL,
    val_bool   boolean,
    val_num    numeric,
    val_text   text COLLATE "C",
    PRIMARY KEY (thing_id, path, wpath, ord)
);
CREATE INDEX sf_num   ON search_flat (wpath, val_num)  WHERE val_num  IS NOT NULL;
CREATE INDEX sf_text  ON search_flat (wpath, val_text) WHERE val_text IS NOT NULL;
CREATE INDEX sf_bool  ON search_flat (wpath, val_bool) WHERE val_bool IS NOT NULL;
CREATE INDEX sf_exists ON search_flat (wpath);
CREATE INDEX sf_trgm  ON search_flat USING gin ((val_text COLLATE "C.utf8") gin_trgm_ops) WHERE val_text IS NOT NULL;
