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
    val category: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val paid: Boolean = false,
    val account: String? = null,
    val balanceApplied: Boolean = false
)

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "account_balances")
data class AccountBalance(
    @PrimaryKey val account: String,
    val balanceCents: Long
)

fun transactionBalanceChange(entry: MoneyEntry): Long {
    if (!entry.balanceApplied || entry.account == null) return 0L

    val cents = kotlin.math.round(entry.amount * 100).toLong()

    return when (entry.type) {
        "SPEND" -> -cents
        "INCOME" -> cents
        "BILL" -> if (entry.paid) -cents else 0L
        else -> 0L
    }
}

@Dao
interface MoneyDao {
    @Query("SELECT * FROM account_balances")
    fun observeAccountBalances(): Flow<List<AccountBalance>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccountBalance(balance: AccountBalance)

    @Update
    suspend fun updateAccountBalance(balance: AccountBalance)

    @Query("""
        UPDATE account_balances
        SET balanceCents = balanceCents + :changeCents
        WHERE account = :account
    """)
    suspend fun adjustAccountBalance(account: String, changeCents: Long): Int

    suspend fun applyBalanceChange(account: String, changeCents: Long) {
        if (changeCents == 0L) return
        require(account == "Bank" || account == "Revolut") {
            "Unknown account"
        }
        check(adjustAccountBalance(account, changeCents) == 1) {
            "Account balance record missing"
        }
    }

    @Transaction
    suspend fun saveAccountBalances(bankCents: Long, revolutCents: Long) {
        updateAccountBalance(AccountBalance("Bank", bankCents))
        updateAccountBalance(AccountBalance("Revolut", revolutCents))
    }

    @Query("SELECT * FROM entries ORDER BY createdAt DESC") fun observeAll(): Flow<List<MoneyEntry>>
    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: Long): MoneyEntry?

    @Transaction
    suspend fun insertWithBalance(entry: MoneyEntry) {
        val shouldApply = entry.account != null &&
            (entry.type == "SPEND" ||
             entry.type == "INCOME" ||
             (entry.type == "BILL" && entry.paid))

        val newEntry = entry.copy(balanceApplied = shouldApply)

        if (shouldApply) {
            val change = transactionBalanceChange(newEntry)
            applyBalanceChange(requireNotNull(newEntry.account), change)
        }

        insert(newEntry)
    }

    @Insert suspend fun insert(entry: MoneyEntry)
    @Transaction
    suspend fun updateWithBalance(entry: MoneyEntry) {
        val original = getEntryById(entry.id) ?: return

        if (original.balanceApplied && original.account != null) {
            applyBalanceChange(
                original.account,
                -transactionBalanceChange(original)
            )
        }

        // Legacy transactions without an assigned account must not
        // suddenly affect current balances when edited.
        val account = if (original.account == null) null else entry.account

        val eligible = account != null &&
            (entry.type == "SPEND" ||
             entry.type == "INCOME" ||
             (entry.type == "BILL" && entry.paid))

        // Do not apply legacy spending or income that was never
        // included in the tracked balance. Allow an unpaid bill
        // to be applied when it is marked paid.
        val shouldApply = eligible && (
            original.balanceApplied ||
            (original.type == "BILL" && entry.type == "BILL" && entry.paid)
        )

        val updated = entry.copy(
            account = account,
            balanceApplied = shouldApply
        )

        if (shouldApply) {
            applyBalanceChange(
                requireNotNull(updated.account),
                transactionBalanceChange(updated)
            )
        }

        update(updated)
    }

    @Update suspend fun update(entry: MoneyEntry)
    @Transaction
    suspend fun deleteWithBalance(entry: MoneyEntry) {
        val original = getEntryById(entry.id) ?: return

        if (original.balanceApplied && original.account != null) {
            val reversal = -transactionBalanceChange(original)
            applyBalanceChange(original.account, reversal)
        }

        delete(original)
    }

    @Delete suspend fun delete(entry: MoneyEntry)

    @Query("SELECT * FROM savings_goals ORDER BY createdAt DESC")
    fun observeSavingsGoals(): Flow<List<SavingsGoal>>

    @Insert
    suspend fun insertSavingsGoal(goal: SavingsGoal)

    @Delete
    suspend fun deleteSavingsGoal(goal: SavingsGoal)
}

@Database(entities = [MoneyEntry::class, SavingsGoal::class, AccountBalance::class], version = 8, exportSchema = false)
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN category TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN paid INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN account TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN balanceApplied INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS account_balances (account TEXT NOT NULL PRIMARY KEY, balanceCents INTEGER NOT NULL)")
            }
        }

        @Volatile private var INSTANCE: ButiDb? = null
        fun get(context: Context): ButiDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ButiDb::class.java, "buti.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                .also { INSTANCE = it }
        }
    }
}
