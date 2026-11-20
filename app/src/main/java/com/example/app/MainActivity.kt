package com.example.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {


    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
        initData()
    }

    private fun initData() {
    }

    private fun initView() {
        binding.form.setOnClickListener {
            startActivity(Intent(this, LeaveActivity::class.java))
        }
        binding.head.setOnClickListener {
            startActivity(
                Intent(this, UserHeadActivity::class.java)
            )
        }
    }

}