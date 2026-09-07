package com.example.tv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class SearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
    }

    override fun onSearchRequested(): Boolean {
        val tvSearchFragment = supportFragmentManager.findFragmentById(R.id.search_fragment) as? TvSearchFragment
        if (tvSearchFragment != null) {
            tvSearchFragment.startRecognition()
            return true
        }
        return super.onSearchRequested()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val searchFragment = supportFragmentManager.findFragmentById(R.id.search_fragment) as? TvSearchFragment
            if (searchFragment != null) {
                val searchView = searchFragment.view
                if (searchView != null) {
                    val searchEditText = findSearchEditText(searchView)
                    if (searchEditText != null && searchEditText.hasFocus()) {
                        if (event.action == android.view.KeyEvent.ACTION_UP) {
                            searchEditText.clearFocus()
                            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        // Prevent activity from closing when the Leanback keyboard dismisses and triggers onBackPressed
        val searchFragment = supportFragmentManager.findFragmentById(R.id.search_fragment) as? TvSearchFragment
        val searchView = searchFragment?.view
        if (searchView != null) {
            val searchEditText = findSearchEditText(searchView)
            if (searchEditText != null && searchEditText.hasFocus()) {
                searchEditText.clearFocus()
                return
            }
        }
        super.onBackPressed()
    }

    private fun findSearchEditText(view: android.view.View): android.view.View? {
        if (view is androidx.leanback.widget.SearchEditText || view.javaClass.simpleName == "SearchEditText") {
            return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSearchEditText(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
