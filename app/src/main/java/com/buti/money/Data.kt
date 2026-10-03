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
    val savingsGoalId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM entries ORDER BY createdAt DESC") fun observeAll(): Flow<List<MoneyEntry>>
    @Insert suspend fun insert(entry: MoneyEntry)
    @Update suspend fun update(entry: MoneyEntry)
    @Delete suspend fun delete(entry: MoneyEntry)

    @Query("SELECT * FROM savings_goals ORDER BY createdAt DESC")
    fun observeSavingsGoals(): Flow<List<SavingsGoal>>

    @Insert
    suspend fun insertSavingsGoal(goal: SavingsGoal)

    @Delete
    suspend fun deleteSavingsGoal(goal: SavingsGoal)
}

@Database(entities = [MoneyEntry::class, SavingsGoal::class], version = 4, exportSchema = false)
abstract class ButiDb : RoomDatabase() {
    abstract fun dao(): MoneyDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN recurring INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS savings_goals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        targetAmount REAL NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN savingsGoalId INTEGER")
            }
        }

        @Volatile private var INSTANCE: ButiDb? = null
        fun get(context: Context): ButiDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ButiDb::class.java, "buti.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { INSTANCE = it }
        }
    }
}
