package com.pupu.taojinbi

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private lateinit var homeFragment: HomeFragment
    private lateinit var settingsFragment: SettingsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigLoader.ensureBundledConfigInstalled(this)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            settingsFragment = SettingsFragment()
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.nav_host, homeFragment, TAG_HOME)
                add(R.id.nav_host, settingsFragment, TAG_SETTINGS)
                hide(settingsFragment)
            }
            bottomNav.selectedItemId = R.id.nav_home
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag(TAG_HOME) as HomeFragment
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as SettingsFragment
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        show(homeFragment)
                        hide(settingsFragment)
                    }
                    homeFragment.refreshStatus()
                    true
                }
                R.id.nav_settings -> {
                    supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        hide(homeFragment)
                        show(settingsFragment)
                    }
                    true
                }
                else -> false
            }
        }

    }

    fun refreshHomeStatus() {
        if (::homeFragment.isInitialized && homeFragment.isAdded) {
            homeFragment.refreshStatus()
        }
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_SETTINGS = "settings"
    }
}
