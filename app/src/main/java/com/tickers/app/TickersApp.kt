package com.tickers.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.tickers.app.data.MarketRepository
import com.tickers.app.data.db.TickersDatabase
import com.tickers.app.data.remote.YahooDataSource
import com.tickers.app.update.Updater
import com.tickers.app.work.RefreshWorker

class TickersApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedulePeriodic(this)
    }
}

class AppContainer(context: Context) {
    private val db = Room.databaseBuilder(context, TickersDatabase::class.java, "tickers.db").build()
    val repository = MarketRepository(db.assetDao(), YahooDataSource.create())
    val updater = Updater(context)
}

val Context.container: AppContainer
    get() = (applicationContext as TickersApp).container
