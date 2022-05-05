package com.techun.aiproyectcoffeepests.view

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.common.base.Objects
import com.techun.aiproyectcoffeepests.R
import com.techun.aiproyectcoffeepests.camera.CameraSource
import com.techun.aiproyectcoffeepests.camera.CameraSourcePreview
import com.techun.aiproyectcoffeepests.camera.GraphicOverlay
import com.techun.aiproyectcoffeepests.camera.WorkflowModel
import com.techun.aiproyectcoffeepests.camera.WorkflowModel.WorkflowState
import com.techun.aiproyectcoffeepests.extensions.goToActivity
import com.techun.aiproyectcoffeepests.objectdetection.MultiObjectProcessor
import com.techun.aiproyectcoffeepests.objectdetection.ProminentObjectProcessor
import com.techun.aiproyectcoffeepests.pestsearch.BottomSheetScrimView
import com.techun.aiproyectcoffeepests.pestsearch.Pest
import com.techun.aiproyectcoffeepests.settings.AboutActivity
import com.techun.aiproyectcoffeepests.settings.PreferenceUtils
import com.techun.aiproyectcoffeepests.settings.SettingsActivity
import java.io.IOException

class LiveObjectDetectionActivity : AppCompatActivity(), View.OnClickListener {
    private var cameraSource: CameraSource? = null
    private var preview: CameraSourcePreview? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var settingsButton: View? = null
    private var aboutButton: View? = null
    private var flashButton: View? = null
    private var promptChip: Chip? = null
    private var promptChipAnimator: AnimatorSet? = null
    private var searchButton: ExtendedFloatingActionButton? = null
    private var searchButtonAnimator: AnimatorSet? = null
    private var workflowModel: WorkflowModel? = null
    private var currentWorkflowState: WorkflowState? = null

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    private var bottomSheetScrimView: BottomSheetScrimView? = null
    private var bottomSheetTitleView: TextView? = null
    private var bottomSheetTitlePest: TextView? = null
    private var bottomSheetDescriptionsPest: TextView? = null
    private var bottomSheetScientificNamePest: TextView? = null
    private var bottomSheetImagePest: ImageView? = null
    private var objectThumbnailForBottomSheet: Bitmap? = null
    private var slidingSheetUpFromHiddenState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_object_detection)

        preview = findViewById(R.id.camera_preview)
        graphicOverlay = findViewById<GraphicOverlay>(R.id.camera_preview_graphic_overlay).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
            cameraSource = CameraSource(this)
        }
        promptChip = findViewById(R.id.bottom_prompt_chip)
        promptChipAnimator = (AnimatorInflater.loadAnimator(
            this,
            R.animator.bottom_prompt_chip_enter
        ) as AnimatorSet).apply {
            setTarget(promptChip)
        }
        searchButton =
            findViewById<ExtendedFloatingActionButton>(R.id.product_search_button).apply {
                setOnClickListener(this@LiveObjectDetectionActivity)
            }
        searchButtonAnimator = (AnimatorInflater.loadAnimator(
            this,
            R.animator.search_button_enter
        ) as AnimatorSet).apply {
            setTarget(searchButton)
        }
        setUpBottomSheet()
        findViewById<View>(R.id.close_button).setOnClickListener(this)
        flashButton = findViewById<View>(R.id.flash_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }
        settingsButton = findViewById<View>(R.id.settings_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }

        aboutButton = findViewById<View>(R.id.about_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }
        setUpWorkflowModel()
    }

    override fun onResume() {
        super.onResume()
        workflowModel?.markCameraFrozen()
        settingsButton?.isEnabled = true
        aboutButton?.isEnabled = true
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
        currentWorkflowState = WorkflowState.NOT_STARTED
        cameraSource?.setFrameProcessor(
            if (PreferenceUtils.isMultipleObjectsMode(this)) {
                MultiObjectProcessor(
                    graphicOverlay!!, workflowModel!!,
                    CUSTOM_MODEL_PATH
                )
            } else {
                ProminentObjectProcessor(
                    graphicOverlay!!, workflowModel!!,
                    CUSTOM_MODEL_PATH
                )
            }
        )
        workflowModel?.setWorkflowState(WorkflowState.DETECTING)
    }

    override fun onPause() {
        super.onPause()
        currentWorkflowState = WorkflowState.NOT_STARTED
        stopCameraPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraSource?.release()
        cameraSource = null
    }

    override fun onBackPressed() {
        if (bottomSheetBehavior?.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_HIDDEN)
        } else {
            super.onBackPressed()
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.product_search_button -> {
                searchButton?.isEnabled = false
                workflowModel?.onSearchButtonClicked()
            }
            R.id.bottom_sheet_scrim_view -> bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_HIDDEN)
            R.id.close_button -> onBackPressed()
            R.id.flash_button -> {
                if (flashButton?.isSelected == true) {
                    flashButton?.isSelected = false
                    cameraSource?.updateFlashMode(Camera.Parameters.FLASH_MODE_OFF)
                } else {
                    flashButton?.isSelected = true
                    cameraSource?.updateFlashMode(Camera.Parameters.FLASH_MODE_TORCH)
                }
            }
            R.id.settings_button -> {
                settingsButton?.isEnabled = false
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.about_button -> {
                aboutButton?.isEnabled = false
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }
    }

    private fun startCameraPreview() {
        val cameraSource = this.cameraSource ?: return
        val workflowModel = this.workflowModel ?: return
        if (!workflowModel.isCameraLive) {
            try {
                workflowModel.markCameraLive()
                preview?.start(cameraSource)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start camera preview!", e)
                cameraSource.release()
                this.cameraSource = null
            }
        }
    }

    private fun stopCameraPreview() {
        if (workflowModel?.isCameraLive == true) {
            workflowModel!!.markCameraFrozen()
            flashButton?.isSelected = false
            preview?.stop()
        }
    }

    private fun setUpBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet))
        bottomSheetBehavior?.setBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    Log.d(TAG, "Bottom sheet new state: $newState")
                    bottomSheetScrimView?.visibility =
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) View.GONE else View.VISIBLE
                    graphicOverlay?.clear()

                    when (newState) {
                        BottomSheetBehavior.STATE_HIDDEN -> workflowModel?.setWorkflowState(
                            WorkflowState.DETECTING
                        )
                        BottomSheetBehavior.STATE_COLLAPSED,
                        BottomSheetBehavior.STATE_EXPANDED,
                        BottomSheetBehavior.STATE_HALF_EXPANDED -> slidingSheetUpFromHiddenState =
                            false
                        BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> {
                        }
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    val searchedObject = workflowModel!!.searchedObject.value
                    if (searchedObject == null || java.lang.Float.isNaN(slideOffset)) {
                        return
                    }

                    val graphicOverlay = graphicOverlay ?: return
                    val bottomSheetBehavior = bottomSheetBehavior ?: return
                    val collapsedStateHeight =
                        bottomSheetBehavior.peekHeight.coerceAtMost(bottomSheet.height)
                    val bottomBitmap = objectThumbnailForBottomSheet ?: return
                    if (slidingSheetUpFromHiddenState) {
                        val thumbnailSrcRect =
                            graphicOverlay.translateRect(searchedObject.boundingBox)
                        bottomSheetScrimView?.updateWithThumbnailTranslateAndScale(
                            bottomBitmap,
                            collapsedStateHeight,
                            slideOffset,
                            thumbnailSrcRect
                        )
                    } else {
                        bottomSheetScrimView?.updateWithThumbnailTranslate(
                            bottomBitmap, collapsedStateHeight, slideOffset, bottomSheet
                        )
                    }
                }
            })

        bottomSheetScrimView =
            findViewById<BottomSheetScrimView>(R.id.bottom_sheet_scrim_view).apply {
                setOnClickListener(this@LiveObjectDetectionActivity)
            }

        bottomSheetTitleView = findViewById(R.id.bottom_sheet_title)
        bottomSheetTitlePest = findViewById(R.id.tvTitle)
        bottomSheetImagePest = findViewById(R.id.imageView)
        bottomSheetDescriptionsPest = findViewById(R.id.tvDescriptions)
        bottomSheetScientificNamePest = findViewById(R.id.tvScientificName)
    }

    private fun setUpWorkflowModel() {
        workflowModel = ViewModelProviders.of(this).get(WorkflowModel::class.java).apply {

            // Observes the workflow state changes, if happens, update the overlay view indicators and
            // camera preview state.
            workflowState.observe(this@LiveObjectDetectionActivity, Observer { workflowState ->
                if (workflowState == null || Objects.equal(
                        currentWorkflowState,
                        workflowState
                    )
                ) {
                    return@Observer
                }
                currentWorkflowState = workflowState
                Log.d(TAG, "Current workflow state: ${workflowState.name}")

                if (PreferenceUtils.isAutoSearchEnabled(this@LiveObjectDetectionActivity)) {
                    stateChangeInAutoSearchMode(workflowState)
                } else {
                    stateChangeInManualSearchMode(workflowState)
                }
            })

            // Observes changes on the object to search, if happens, show detected object labels as
            // product search results.
            objectToSearch.observe(this@LiveObjectDetectionActivity) { detectObject ->
                val pestList: List<Pest> = detectObject.labels.map { label ->
                    val information = when (label.text) {
                        "Antracnosis" -> {
                            getString(R.string.msg_antracnosis)
                        }
                        "Ojo de gallo" -> {
                            getString(R.string.msg_ojo_de_gallo)
                        }
                        "Roya" -> {
                            getString(R.string.msg_roya)
                        }
                        else -> {
                            getString(R.string.msg_roya)
                        }
                    }
                    val scientificName = when (label.text) {
                        "Antracnosis" -> {
                            "Colletotrichum coffeanum"
                        }
                        "Ojo de gallo" -> {
                            "Mycena citricolor"
                        }
                        "Roya" -> {
                            "Hemileia vastatrix"
                        }
                        else -> {
                            "Hemileia vastatrix"
                        }
                    }

                    val imgPreview = when (label.text) {
                        "Antracnosis" -> {
                            R.drawable.antraconosis
                        }
                        "Ojo de gallo" -> {
                            R.drawable.ojo_de_gallo
                        }
                        "Roya" -> {
                            R.drawable.roya
                        }
                        else -> {
                            R.drawable.roya
                        }
                    }
                    Pest(
                        label.text,
                        scientificName,
                        information,
                        imgPreview/* *//* imageUrl *//*, label.text, "${label.confidence}" *//* subtitle */
                    )
                }
                workflowModel?.onSearchCompleted(detectObject, pestList)
            }

            // Observes changes on the object that has search completed, if happens, show the bottom sheet
            // to present search result.
            searchedObject.observe(this@LiveObjectDetectionActivity) { searchedObject ->
                val productList = searchedObject.pestList

                objectThumbnailForBottomSheet = searchedObject.getObjectThumbnail()
                bottomSheetTitleView?.text = resources.getQuantityString(
                    R.plurals.bottom_sheet_title,
                    productList.size,
                    productList.size
                )
                bottomSheetTitlePest?.text = productList.firstOrNull()?.title ?: "n/a" //Load Title
                bottomSheetImagePest?.setImageResource(
                    productList.firstOrNull()?.imageUrl ?: R.drawable.tfl2_logo
                ) //Load ImagePreview
                bottomSheetDescriptionsPest?.text =
                    (productList.firstOrNull()?.description ?: "n/a") //Load Description
                bottomSheetScientificNamePest?.text =
                    (productList.firstOrNull()?.subtitle ?: "n/a") //Load Scientific Name
                slidingSheetUpFromHiddenState = true
                bottomSheetBehavior?.peekHeight =
                    preview?.height?.div(2) ?: BottomSheetBehavior.PEEK_HEIGHT_AUTO
                bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }

    private fun stateChangeInAutoSearchMode(workflowState: WorkflowState) {
        val wasPromptChipGone = promptChip!!.visibility == View.GONE

        searchButton?.visibility = View.GONE
        when (workflowState) {
            WorkflowState.DETECTING, WorkflowState.DETECTED, WorkflowState.CONFIRMING -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(
                    if (workflowState == WorkflowState.CONFIRMING)
                        R.string.prompt_hold_camera_steady
                    else
                        R.string.prompt_point_at_a_bird
                )
                startCameraPreview()
            }
            WorkflowState.CONFIRMED -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_searching)
                stopCameraPreview()
            }
            WorkflowState.SEARCHING -> {
                promptChip?.visibility = View.GONE
                stopCameraPreview()
            }
            WorkflowState.SEARCHED -> {
                stopCameraPreview()
            }
            else -> promptChip?.visibility = View.GONE
        }

        val shouldPlayPromptChipEnteringAnimation =
            wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        if (shouldPlayPromptChipEnteringAnimation && promptChipAnimator?.isRunning == false) {
            promptChipAnimator?.start()
        }
    }

    private fun stateChangeInManualSearchMode(workflowState: WorkflowState) {
        val wasPromptChipGone = promptChip?.visibility == View.GONE
        val wasSearchButtonGone = searchButton?.visibility == View.GONE

        when (workflowState) {
            WorkflowState.DETECTING, WorkflowState.DETECTED, WorkflowState.CONFIRMING -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_point_at_an_object)
                searchButton?.visibility = View.GONE
                startCameraPreview()
            }
            WorkflowState.CONFIRMED -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.VISIBLE
                searchButton?.isEnabled = true
                searchButton?.setBackgroundColor(Color.WHITE)
                startCameraPreview()
            }
            WorkflowState.SEARCHING -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.VISIBLE
                searchButton?.isEnabled = false
                searchButton?.setBackgroundColor(Color.GRAY)
                stopCameraPreview()
            }
            WorkflowState.SEARCHED -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.GONE
                stopCameraPreview()
            }
            else -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.GONE
            }
        }

        val shouldPlayPromptChipEnteringAnimation =
            wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        promptChipAnimator?.let {
            if (shouldPlayPromptChipEnteringAnimation && !it.isRunning) it.start()
        }

        val shouldPlaySearchButtonEnteringAnimation =
            wasSearchButtonGone && searchButton?.visibility == View.VISIBLE
        searchButtonAnimator?.let {
            if (shouldPlaySearchButtonEnteringAnimation && !it.isRunning) it.start()
        }
    }

    companion object {
        private const val TAG = "LiveObjectDetection"
        private const val CUSTOM_MODEL_PATH = "plagas_detector_v1.tflite"
    }
}