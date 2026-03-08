package com.inxy.notebook2

import android.os.Bundle
import android.util.Log
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.inxy.notebook2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 使用安全的方式获取 NavController
        setupNavigation()
    }

    private fun setupNavigation() {
        try {
            // 通过 FragmentManager 获取 NavHostFragment
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_container) as? NavHostFragment

            if (navHostFragment == null) {
                Log.e("MainActivity", "NavHostFragment not found!")
                return
            }

            val navController = navHostFragment.navController

            val appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.navigation_home,
                    R.id.navigation_dashboard,
                    R.id.navigation_notifications
                )
            )

            setupActionBarWithNavController(navController, appBarConfiguration)

            // 设置 BottomNavigationView
            binding.navView.setupWithNavController(navController)

        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up navigation", e)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return try {
            val navController = findNavController(R.id.nav_host_container)
            navController.navigateUp() || super.onSupportNavigateUp()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in navigateUp", e)
            super.onSupportNavigateUp()
        }
    }
}