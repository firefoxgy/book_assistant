package com.bookassistant.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class Book(@PrimaryKey val id: String, val title: String, val format: String, val path: String, val importedAt: Long, val position: Int = 0, @ColumnInfo(defaultValue = "0") val totalPassages: Int = 0)

@Entity(tableName = "passages", foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class Passage(@PrimaryKey val id: String, val bookId: String, val chapter: String, val position: Int, val english: String, val chinese: String? = null)

@Entity(tableName = "vocabulary")
data class Word(@PrimaryKey val key: String, val display: String, val chinese: String, val phonetic: String, val definition: String, val example: String, val source: String, val addedAt: Long, @ColumnInfo(defaultValue = "''") val exampleChinese: String = "", @ColumnInfo(defaultValue = "''") val exampleAttribution: String = "")

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY importedAt DESC") fun all(): Flow<List<Book>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun get(id: String): Book?
    @Query("SELECT * FROM passages WHERE bookId = :id ORDER BY position") fun passages(id: String): Flow<List<Passage>>
    @Insert suspend fun insert(book: Book)
    @Insert suspend fun insertPassages(passages: List<Passage>)
    @Query("UPDATE passages SET chinese = :translation WHERE id = :id") suspend fun translate(id: String, translation: String)
    @Query("UPDATE passages SET chapter = :chapter WHERE id = :id") suspend fun chapter(id: String, chapter: String)
    @Query("UPDATE books SET position = :position WHERE id = :id") suspend fun position(id: String, position: Int)
    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface WordDao {
    @Query("SELECT * FROM vocabulary ORDER BY addedAt DESC") fun all(): Flow<List<Word>>
    @Query("SELECT * FROM vocabulary WHERE key = :key") suspend fun get(key: String): Word?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(word: Word)
    @Query("DELETE FROM vocabulary WHERE key = :key") suspend fun delete(key: String)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN totalPassages INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN exampleChinese TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN exampleAttribution TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [Book::class, Passage::class, Word::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun words(): WordDao
}
