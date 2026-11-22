package app.photos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Journal writes are synchronous and survive process death. Never marks a file complete before verification. */
class Journal(context: Context) : SQLiteOpenHelper(context, "transfers.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE copies (key TEXT PRIMARY KEY, uri TEXT NOT NULL, hash TEXT NOT NULL, complete INTEGER NOT NULL)")
        createSync(db)
        createLinks(db)
        createHashCache(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSync(db)
        if (oldVersion < 3) {
            createLinks(db)
            db.rawQuery("SELECT key,uri,hash FROM copies WHERE complete=1", null).use { c ->
                while (c.moveToNext()) {
                    val parts = c.getString(0).split('|', limit = 4)
                    if (parts.size == 4 && parts[0] == "backup") addLink(db, parts[1], parts[2], c.getString(1), c.getString(2))
                }
            }
        }
        if (oldVersion < 4) createHashCache(db)
    }
    private fun createHashCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hash_cache (uri TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, hash TEXT NOT NULL)")
    }
    /**
     * Speeds up drive indexing only. A cached hash is a hint: any match is re-hashed before it is
     * trusted, and a stale entry can only cause an extra copy, never a skipped one.
     */
    fun cachedHash(uri: String, size: Long, modified: Long): String? {
        if (size <= 0 || modified <= 0) return null
        return readableDatabase.rawQuery("SELECT hash FROM hash_cache WHERE uri=? AND size=? AND modified=?", arrayOf(uri, size.toString(), modified.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }
    fun cacheHash(uri: String, size: Long, modified: Long, hash: String) {
        if (size <= 0 || modified <= 0) return
        val values = ContentValues().apply { put("uri", uri); put("size", size); put("modified", modified); put("hash", hash) }
        writableDatabase.insertWithOnConflict("hash_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun createLinks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_links (drive TEXT NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL, hash TEXT NOT NULL, kept INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(drive,source,target))")
    }
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("INSERT OR IGNORE INTO backup_links(drive,source,target,hash) SELECT drive,source,target,hash FROM sync WHERE state='present' AND target<>''")
    }
    data class BackupLink(val source: String, val target: String, val hash: String, val kept: Boolean)
    fun links(drive: String): List<BackupLink> = readableDatabase.rawQuery("SELECT source,target,hash,kept FROM backup_links WHERE drive=?", arrayOf(drive)).use { c ->
        buildList { while(c.moveToNext()) add(BackupLink(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1)) }
    }
    private fun addLink(db: SQLiteDatabase, drive: String, source: String, target: String, hash: String) {
        val values = ContentValues().apply { put("drive", drive); put("source", source); put("target", target); put("hash", hash) }
        db.insertWithOnConflict("backup_links", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun keep(target: String, kept: Boolean) {
        writableDatabase.update("backup_links", ContentValues().apply { put("kept", if (kept) 1 else 0) }, "target=?", arrayOf(target))
    }
    fun deletedTarget(target: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("copies", "uri=?", arrayOf(target))
            db.update("sync", ContentValues().apply { put("state", "missing"); put("target", ""); put("checked", System.currentTimeMillis()) }, "target=?", arrayOf(target))
            db.delete("backup_links", "target=?", arrayOf(target))
            db.delete("hash_cache", "uri=?", arrayOf(target))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun createSync(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync (drive TEXT NOT NULL, source TEXT NOT NULL, hash TEXT NOT NULL, target TEXT NOT NULL, state TEXT NOT NULL, checked INTEGER NOT NULL, verified INTEGER NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, PRIMARY KEY(drive, source))")
    }
    fun statuses(drive: String): Map<String, SyncRecord> = readableDatabase.query("sync", null, "drive=?", arrayOf(drive), null, null, null).use { c ->
        buildMap {
            while (c.moveToNext()) {
                fun text(name: String) = c.getString(c.getColumnIndexOrThrow(name))
                fun number(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
                put(text("source"), SyncRecord(text("hash"), text("target"), text("state"), number("checked"), number("verified"), number("size"), number("modified")))
            }
        }
    }
    fun sync(drive: String, source: String, record: SyncRecord) {
        val values = ContentValues().apply {
            put("drive", drive); put("source", source); put("hash", record.hash); put("target", record.target)
            put("state", record.state); put("checked", record.checked); put("verified", record.verified)
            put("size", record.size); put("modified", record.modified)
        }
        check(writableDatabase.insertWithOnConflict("sync", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        if (record.state == "present" && record.target.isNotEmpty()) addLink(writableDatabase, drive, source, record.target, record.hash)
    }
    data class Record(val uri: String, val hash: String, val complete: Boolean)
    fun get(key: String): Record? = readableDatabase.query("copies", null, "key=?", arrayOf(key), null, null, null).use { c ->
        if (!c.moveToFirst()) null else Record(c.getString(c.getColumnIndexOrThrow("uri")), c.getString(c.getColumnIndexOrThrow("hash")), c.getInt(c.getColumnIndexOrThrow("complete")) == 1)
    }
    fun put(key: String, uri: String, hash: String, complete: Boolean) {
        val values = ContentValues().apply { put("key", key); put("uri", uri); put("hash", hash); put("complete", if (complete) 1 else 0) }
        check(writableDatabase.insertWithOnConflict("copies", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
}
