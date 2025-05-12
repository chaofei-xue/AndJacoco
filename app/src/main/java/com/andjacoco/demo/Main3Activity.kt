package com.andjacoco.demo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.andjacoco.demo.databinding.ActivityMainBinding

class Main3Activity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Hello.Toast(this, "hello132")


        binding.tv.setOnClickListener {
            startActivity(Intent(this, SecondActivity::class.java))

            Toast.makeText(this, "Test Info", Toast.LENGTH_LONG).show()

            Toast.makeText(this, "Test Code1111", Toast.LENGTH_LONG).show()
            Toast.makeText(this, "Test Code2222", Toast.LENGTH_LONG).show()
            Toast.makeText(this, "Test Code33333", Toast.LENGTH_LONG).show()
        }

        Hello.hello(false)

    }


}