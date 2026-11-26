ALTER TABLE dii_replay_db_compare_script_config
    ADD COLUMN (
        connection_json TEXT NULL COMMENT 'CUSTOM source metadata. NULL uses BASE.',
        password_ciphertext TEXT NULL COMMENT 'Versioned authenticated ciphertext for the source password.'
    );
