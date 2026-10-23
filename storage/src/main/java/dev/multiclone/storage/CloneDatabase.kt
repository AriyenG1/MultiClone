package dev.multiclone.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceRow(@PrimaryKey val id: String, val label: String, val packageName: String, val json: String)
@Entity(tableName = "clones", indices = [Index(value = ["packageName"], unique = true)])
data class CloneRow(
    @PrimaryKey val id: String, val sourceId: String, val label: String, val packageName: String,
    val plan: String, val artifacts: String = "[]", val versionCode: Long = 0,
    val state: String = "BUILDING", val message: String = "", val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val wasInstalled: Boolean = false,
)
@Entity(tableName = "jobs")
data class JobRow(
    @PrimaryKey val id: String, val sourceId: String, val label: String, val total: Long,
    val completed: Long = 0, val state: String = "QUEUED", val iconPath: String? = null,
    val updateCloneId: String? = null, val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'[]'") val cloneNames: String = "[]",
)
@Dao
interface CloneDao {
    @Query("SELECT * FROM sources ORDER BY label") fun observeSources(): Flow<List<SourceRow>>
    @Query("SELECT * FROM clones ORDER BY createdAt DESC") fun observeClones(): Flow<List<CloneRow>>
    @Query("SELECT * FROM jobs ORDER BY createdAt DESC") fun observeJobs(): Flow<List<JobRow>>
    @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id: String): SourceRow?
    @Query("SELECT * FROM sources") suspend fun sources(): List<SourceRow>
    @Query("SELECT * FROM clones") suspend fun clones(): List<CloneRow>
    @Query("SELECT * FROM clones WHERE id=:id") suspend fun clone(id: String): CloneRow?
    @Query("SELECT * FROM jobs WHERE state='QUEUED' ORDER BY createdAt LIMIT 1") suspend fun nextJob(): JobRow?
    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): JobRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSource(row: SourceRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveClone(row: CloneRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveJob(row: JobRow)
    @Query("UPDATE jobs SET state='PAUSED', message='Interrupted. Resume when ready.' WHERE state='RUNNING'") suspend fun recoverJobs()
    @Query("UPDATE clones SET state='FAILED', message='Build interrupted. Create another clone to retry.' WHERE state='BUILDING'") suspend fun recoverClones()
    @Query("UPDATE jobs SET state='QUEUED' WHERE state='PAUSED'") suspend fun resumeJobs()
    @Query("UPDATE jobs SET state='PAUSED', message='Paused by user.' WHERE state IN ('QUEUED','RUNNING')") suspend fun pauseJobs()
    @Query("UPDATE jobs SET state='CANCELLED', message='Cancelled; a clone already being processed may finish.' WHERE id=:id AND state IN ('QUEUED','RUNNING','PAUSED')") suspend fun cancelJob(id: String)
    @Query("DELETE FROM clones WHERE id=:id") suspend fun deleteClone(id: String)
    @Query("UPDATE jobs SET state='CANCELLED', message='Clone deleted.' WHERE updateCloneId=:id AND state IN ('QUEUED','RUNNING','PAUSED')") suspend fun cancelUpdates(id: String)
}
@Database(entities = [SourceRow::class, CloneRow::class, JobRow::class], version = 3, exportSchema = true)
abstract class CloneDatabase : RoomDatabase() {
    abstract fun clones(): CloneDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clones ADD COLUMN wasInstalled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE clones SET wasInstalled=1 WHERE state IN ('INSTALLED','TESTED') OR message='Not currently installed. Signing identity retained.'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN cloneNames TEXT NOT NULL DEFAULT '[]'")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, CloneDatabase::class.java, "multiclone.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
