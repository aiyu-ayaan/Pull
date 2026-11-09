package com.aiyu.pull.di

import android.content.Context
import androidx.room.Room
import com.aiyu.pull.data.DownloadDao
import com.aiyu.pull.data.PullDatabase
import com.aiyu.pull.engine.segmentClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PullDatabase =
        Room.databaseBuilder(context, PullDatabase::class.java, "pull.db").build()

    @Provides
    fun downloadDao(db: PullDatabase): DownloadDao = db.downloads()

    /** HTTP/1.1 only, so every segment gets its own TCP connection. */
    @Provides @Singleton
    fun httpClient(): OkHttpClient = segmentClient()
}
