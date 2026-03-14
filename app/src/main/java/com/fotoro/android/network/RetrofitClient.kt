package com.fotoro.android.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl("http://10.42.0.1:8080/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val fotoService: FotoService = retrofit.create(FotoService::class.java)
}
