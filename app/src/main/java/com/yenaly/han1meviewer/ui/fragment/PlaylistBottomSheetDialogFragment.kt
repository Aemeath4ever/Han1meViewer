package com.yenaly.han1meviewer.ui.fragment

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.adapter.HanimeVideoRvAdapter
import com.yenaly.han1meviewer.ui.viewmodel.VideoViewModel
import com.yenaly.han1meviewer.util.calculateSpanCount
import com.yenaly.han1meviewer.util.openVideo

class PlaylistBottomSheetFragment : BottomSheetDialogFragment() {
    companion object {
        const val TAG = "PlaylistBottomSheetFragment"
    }

    private val viewModel: VideoViewModel by viewModels({ requireParentFragment() })
    private var videoCount = 0
    private lateinit var adapter: HanimeVideoRvAdapter
    private lateinit var recyclerView: RecyclerView
    private var savedSheetState: Int = BottomSheetBehavior.STATE_COLLAPSED
    private var savedLayoutManagerState: Parcelable? = null

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.peekHeight = resources.getDimensionPixelSize(R.dimen.bottom_sheet_min_height)
            behavior.isFitToContents = true
            behavior.state = savedSheetState
            it.minimumHeight = resources.getDimensionPixelSize(R.dimen.bottom_sheet_min_height)
        }
    }

    override fun onStop() {
        super.onStop()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            savedSheetState = BottomSheetBehavior.from(it).state
        }
        savedLayoutManagerState = recyclerView.layoutManager?.onSaveInstanceState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_bottom_sheet_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = HanimeVideoRvAdapter(onItemClick = { item ->
            openVideo(item.videoCode)
        })
        recyclerView = view.findViewById(R.id.rv_vertical_list)
        val countText = view.findViewById<TextView>(R.id.video_count)
        recyclerView.adapter = adapter
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener{
                override fun onGlobalLayout() {
                    recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val spanCount = calculateSpanCount(recyclerView,180)
                    recyclerView.layoutManager = GridLayoutManager(requireContext(),spanCount)
                }
            }
        )

        viewModel.videoList.observe(viewLifecycleOwner) { list ->
            videoCount = list.size
            countText.text = getString(R.string.blank_brackets,videoCount)
            adapter.submitList(list)
            if (savedLayoutManagerState != null) {
                recyclerView.post {
                    recyclerView.layoutManager?.onRestoreInstanceState(savedLayoutManagerState)
                    savedLayoutManagerState = null
                }
            }
        }

        recyclerView.addOnLayoutChangeListener {  _, left, _, right, _, _, _, _, _ ->
            val newWidth = right - left
            if (newWidth > 0) {
                val newSpanCount = calculateSpanCount(recyclerView, 180)
                val existingManager = recyclerView.layoutManager as? GridLayoutManager
                if (existingManager == null || existingManager.spanCount != newSpanCount) {
                    recyclerView.layoutManager = GridLayoutManager(requireContext(), newSpanCount)
                }
            }
        }
    }
}