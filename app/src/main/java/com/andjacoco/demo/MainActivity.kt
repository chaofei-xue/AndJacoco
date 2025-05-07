package com.andjacoco.demo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.andjacoco.demo.databinding.ActivityMainBinding

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Hello.Toast(this, "hello132")

        binding.tv.setOnClickListener {
            startActivity(Intent(this, SecondActivity::class.java))
        }

        Hello.hello(false)

    }


}