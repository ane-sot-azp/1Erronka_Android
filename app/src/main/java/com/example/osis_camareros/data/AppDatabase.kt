package com.example.osis_camareros.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.osis_camareros.util.HashingUtil

@Database(entities = [User::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "osis_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Pre-populate database
                            val hashUtil = HashingUtil()
                            db.execSQL(
                                "INSERT INTO users (email, password, fullName) VALUES ('admin@example.com', '${hashUtil.hashPassword("123456")}', 'Administrador')"
                            )
                            db.execSQL(
                                "INSERT INTO users (email, password, fullName) VALUES ('user@example.com', '${hashUtil.hashPassword("password")}', 'Usuario Prueba')"
                            )
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
