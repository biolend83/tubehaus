export APP_TUBEHAUS_DB_PASSWORD="$(derive_entropy "${app_entropy_identifier}-db-password")"
export APP_TUBEHAUS_HMAC_KEY="$(derive_entropy "${app_entropy_identifier}-hmac-key")"
# invidious-companion takes a key of exactly 16 characters
export APP_TUBEHAUS_COMPANION_KEY="$(derive_entropy "${app_entropy_identifier}-companion-key" | head -c16)"
