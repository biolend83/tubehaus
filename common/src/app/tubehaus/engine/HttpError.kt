package app.tubehaus.engine

/** An answer other than 2xx. An IOException like a dropped connection, so both are caught alike. */
class HttpError(val code: Int, msg: String) : okio.IOException(msg)
