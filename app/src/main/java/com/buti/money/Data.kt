package com.buti.money

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entries")
data class MoneyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Double,
    val type: String, // INCOME, BILL, SPEND, SAVING
    val dueDay: Int? = null,
    val recurring: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM entries ORDER BY createdAt DESC") fun observeAll(): Flow<List<MoneyEntry>>
    @Insert suspend fun insert(entry: MoneyEntry)
    @Update suspend fun update(entry: MoneyEntry)
    @Delete suspend fun delete(entry: MoneyEntry)
}

@Database(entities = [MoneyEntry::class], version = 2, exportSchema = false)
abstract class ButiDb : RoomDatabase() {
    abstract fun dao(): MoneyDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN recurring INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var INSTANCE: ButiDb? = null
        fun get(context: Context): ButiDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ButiDb::class.java, "buti.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
