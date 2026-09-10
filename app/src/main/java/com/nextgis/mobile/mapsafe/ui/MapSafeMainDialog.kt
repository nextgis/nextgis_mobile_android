package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import com.nextgis.mobile.R
import com.nextgis.mobile.activity.MainActivity

/** One compact MapSafe launcher with Safeguard and Access tabs. */
class MapSafeMainDialog : DialogFragment() {
    private lateinit var safeguardTab: TextView
    private lateinit var accessTab: TextView
    private lateinit var workflowDescription: TextView
    private lateinit var tabContent: LinearLayout
    private var activeTab = DESTINATION_SAFEGUARD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTab = savedInstanceState?.getString(STATE_ACTIVE_TAB)
            ?: arguments?.getString(ARG_INITIAL_TAB)
            ?: DESTINATION_SAFEGUARD
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ACTIVE_TAB, activeTab)
    }

    override fun onStart() {
        super.onStart()
        anchorDialogToTop()
    }

    private fun anchorDialogToTop() {
        dialog?.window?.let { window ->
            window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            window.attributes = window.attributes.apply {
                y = MapSafeUi.dp(requireContext(), 18)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        tabContent = MapSafeUi.page(context).apply {
            setPadding(0, MapSafeUi.dp(context, 10), 0, 0)
        }
        safeguardTab = tabButton("Safeguard") { selectTab(DESTINATION_SAFEGUARD) }
        accessTab = tabButton("Access") { selectTab(DESTINATION_ACCESS) }
        workflowDescription = MapSafeUi.text(context, "", 15f, MapSafeUi.TEXT).apply {
            setPadding(0, 0, 0, MapSafeUi.dp(context, 14))
        }

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                MapSafeUi.dp(context, 4),
                MapSafeUi.dp(context, 4),
                MapSafeUi.dp(context, 4),
                MapSafeUi.dp(context, 4)
            )
            background = MapSafeUi.rounded(
                context,
                MapSafeUi.GREEN_PALE,
                MapSafeUi.GREEN_PALE,
                radiusDp = 12,
                strokeWidthDp = 0
            )
            addView(safeguardTab, LinearLayout.LayoutParams(0, MapSafeUi.dp(context, 44), 1f))
            addView(accessTab, LinearLayout.LayoutParams(0, MapSafeUi.dp(context, 44), 1f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, MapSafeUi.dp(context, 4)) }
        }

        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 8), MapSafeUi.dp(context, 8), MapSafeUi.dp(context, 8), 0)
            addView(MapSafeUi.logoWordmark(context))
            addView(workflowDescription)
            addView(tabs)
            addView(tabContent)
        }
        renderTab()
        return AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Back", null)
            .create()
    }

    private fun selectTab(tab: String) {
        if (activeTab == tab) return
        activeTab = tab
        renderTab(animate = true)
    }

    private fun renderTab(animate: Boolean = false) {
        if (!::tabContent.isInitialized) return
        styleTab(safeguardTab, activeTab == DESTINATION_SAFEGUARD)
        styleTab(accessTab, activeTab == DESTINATION_ACCESS)
        workflowDescription.text = if (activeTab == DESTINATION_ACCESS) {
            "Verify, decrypt and display shared datasets"
        } else {
            "Anonymise, encrypt, upload, or notarise a dataset for sharing."
        }
        tabContent.removeAllViews()
        if (activeTab == DESTINATION_ACCESS) renderAccess() else renderSafeguard()
        tabContent.post {
            if (isAdded) anchorDialogToTop()
        }
        if (animate) {
            tabContent.animate().cancel()
            tabContent.alpha = 0f
            tabContent.translationY = MapSafeUi.dp(requireContext(), 4).toFloat()
            tabContent.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150L)
                .start()
        }
    }

    private fun renderSafeguard() {
        val selectedName = (activity as? MainActivity)?.mapFragment?.selectedLayer?.name
        tabContent.addView(selectedDatasetRow(selectedName))
        tabContent.addView(workflowList(
            workflowRow(
                R.drawable.ic_mapsafe_halo,
                "Halo Masking",
                "Displace locations within a chosen range",
                "Open Halo Masking"
            ) {
                openLayerAction("Halo masking") {
                    DonutMaskingDialog().show(parentFragmentManager, DonutMaskingDialog.TAG)
                }
            },
            workflowRow(
                R.drawable.ic_mapsafe_hexagon,
                "Hexagonal Binning",
                "Aggregate points into H3 cells",
                "Open Hexagonal Binning"
            ) {
                openLayerAction("Hexagonal binning") {
                    HexabinningDialog().show(parentFragmentManager, HexabinningDialog.TAG)
                }
            },
            workflowRow(
                R.drawable.ic_mapsafe_lock,
                "Encrypt",
                "Protect one or more datasets as separate PGP packages",
                "Open Encrypt"
            ) {
                dismiss()
                MapSafeArtifactSelectionDialog.forEncryption()
                    .show(parentFragmentManager, MapSafeArtifactSelectionDialog.TAG)
            },
            workflowRow(
                R.drawable.ic_mapsafe_upload,
                "Upload to Community",
                "Share selected anonymised or encrypted outputs",
                "Open Upload to Community"
            ) {
                dismiss()
                MapSafeArtifactSelectionDialog.forCommunityUpload()
                    .show(parentFragmentManager, MapSafeArtifactSelectionDialog.TAG)
            },
            workflowRow(
                R.drawable.ic_mapsafe_badge_check,
                "Notarise Package",
                "Anchor a package filename and hash on the blockchain",
                "Open Notarise Package"
            ) {
                dismiss()
                IntegrityRecordDialog.forSafeguardFeatures()
                    .show(parentFragmentManager, "IntegrityRecordDialog")
            }
        ))
        if (selectedName == null) {
            tabContent.addView(MapSafeUi.card(
                requireContext(),
                MapSafeUi.sectionTitle(requireContext(), "Need a compatible point layer?"),
                MapSafeUi.text(
                    requireContext(),
                    "Load the bundled 23-point North Whangārei case-study dataset and select it automatically.",
                    13f
                ),
                MapSafeUi.compactOutlineButton(requireContext(), "Use sample dataset") {
                    useSampleDataset(openAnonymise = false, destination = DESTINATION_SAFEGUARD)
                },
                pale = true
            ))
        }
        tabContent.addView(workflowList(
            workflowRow(
                R.drawable.ic_mapsafe_settings,
                "Security & Sharing",
                "Identity, keys, network, and save folder",
                "Open Security & Sharing"
            ) {
                startActivity(Intent(requireContext(), MapSafeSecurityActivity::class.java))
            }
        ))
    }

    private fun renderAccess() {
        tabContent.addView(workflowList(
            workflowRow(
                R.drawable.ic_mapsafe_community,
                "Community Packages",
                "Browse keys, anonymised datasets, and encrypted packages",
                "Open Community Packages"
            ) {
                dismiss()
                AccessFeaturesDialog().show(parentFragmentManager, "AccessFeaturesDialog")
            },
            workflowRow(
                R.drawable.ic_mapsafe_file_check,
                "Verify Encrypted File",
                "Choose a local PGP package and check its SHA-256",
                "Open Verify Encrypted File"
            ) {
                dismiss()
                IntegrityRecordDialog.forAccessFeatures()
                    .show(parentFragmentManager, "IntegrityRecordDialog")
            }
        ))
        tabContent.addView(MapSafeUi.infoCard(
            requireContext(),
            "Decryption follows verification",
            "After the local package hash is calculated, Next: Decrypt opens the existing Decrypt & Access screen."
        ))
    }

    private fun workflowRow(
        iconRes: Int,
        title: String,
        description: String,
        accessibilityLabel: String,
        action: () -> Unit
    ): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = MapSafeUi.dp(context, 68)
            setPadding(
                MapSafeUi.dp(context, 12),
                MapSafeUi.dp(context, 9),
                MapSafeUi.dp(context, 10),
                MapSafeUi.dp(context, 9)
            )
            isClickable = true
            isFocusable = true
            contentDescription = accessibilityLabel
            background = RippleDrawable(
                ColorStateList.valueOf(0x18256b2b),
                ColorDrawable(Color.TRANSPARENT),
                ColorDrawable(Color.WHITE)
            )
            setOnClickListener { action() }

            addView(ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(MapSafeUi.GREEN)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    MapSafeUi.dp(context, 8),
                    MapSafeUi.dp(context, 8),
                    MapSafeUi.dp(context, 8),
                    MapSafeUi.dp(context, 8)
                )
                background = MapSafeUi.rounded(
                    context,
                    MapSafeUi.GREEN_PALE,
                    MapSafeUi.GREEN_PALE,
                    radiusDp = 10,
                    strokeWidthDp = 0
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                MapSafeUi.dp(context, 38),
                MapSafeUi.dp(context, 38)
            ))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(MapSafeUi.dp(context, 11), 0, MapSafeUi.dp(context, 8), 0)
                addView(MapSafeUi.text(context, title, 15f, MapSafeUi.TEXT, bold = true))
                addView(MapSafeUi.text(context, description, 12.5f, MapSafeUi.MUTED).apply {
                    setPadding(0, MapSafeUi.dp(context, 2), 0, 0)
                })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(MapSafeUi.text(context, "›", 27f, MapSafeUi.MUTED).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                MapSafeUi.dp(context, 20),
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun workflowList(vararg rows: View): LinearLayout {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MapSafeUi.rounded(context, Color.WHITE, MapSafeUi.BORDER, radiusDp = 14)
            rows.forEachIndexed { index, row ->
                addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                if (index < rows.lastIndex) {
                    addView(View(context).apply { setBackgroundColor(0xffe1e7e1.toInt()) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            MapSafeUi.dp(context, 1)
                        ).apply { setMargins(MapSafeUi.dp(context, 12), 0, MapSafeUi.dp(context, 12), 0) }
                    )
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, MapSafeUi.dp(context, 12)) }
        }
    }

    private fun selectedDatasetRow(selectedName: String?): View = workflowList(
        workflowRow(
            R.drawable.ic_mapsafe_map,
            selectedName?.let { "Selected dataset: $it" } ?: "No dataset selected",
            selectedName?.let { "Current map layer" } ?: "Select a point layer on the map or use the sample below",
            if (selectedName == null) "Select dataset on map" else "Change selected dataset"
        ) { dismiss() }
    ).apply {
        alpha = if (selectedName == null) 0.78f else 1f
    }

    private fun openLayerAction(name: String, action: () -> Unit) {
        if ((activity as? MainActivity)?.mapFragment?.selectedLayer == null) {
            showDatasetRequired(name)
            return
        }
        dismiss()
        action()
    }

    private fun showDatasetRequired(actionName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Select a dataset first")
            .setMessage(
                "$actionName needs an active point dataset. Select one on the map or load the bundled sample dataset."
            )
            .setNegativeButton("Return to map") { _, _ -> dismiss() }
            .setPositiveButton("Load sample dataset") { _, _ ->
                useSampleDataset(openAnonymise = false, destination = DESTINATION_SAFEGUARD)
            }
            .show()
    }

    private fun useSampleDataset(openAnonymise: Boolean, destination: String?) {
        parentFragmentManager.setFragmentResult(
            REQUEST_LOAD_SAMPLE_POINTS,
            Bundle().apply {
                putBoolean(RESULT_OPEN_ANONYMISE, openAnonymise)
                destination?.let { putString(RESULT_OPEN_DESTINATION, it) }
            }
        )
        dismiss()
    }

    private fun tabButton(label: String, action: () -> Unit): TextView =
        MapSafeUi.text(requireContext(), label, 15f, MapSafeUi.TEXT, bold = true).apply {
        text = label
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun styleTab(button: TextView, selected: Boolean) {
        button.setTextColor(if (selected) MapSafeUi.GREEN_TEXT else MapSafeUi.MUTED)
        button.background = MapSafeUi.rounded(
            requireContext(),
            if (selected) Color.WHITE else Color.TRANSPARENT,
            Color.TRANSPARENT,
            radiusDp = 9,
            strokeWidthDp = 0
        )
        ViewCompat.setElevation(button, if (selected) MapSafeUi.dp(requireContext(), 3).toFloat() else 0f)
        button.contentDescription = "${button.text} tab${if (selected) ", selected" else ""}"
    }

    companion object {
        const val TAG = "MapSafeMainDialog"
        const val REQUEST_LOAD_SAMPLE_POINTS = "mapsafe_load_sample_points_request"
        const val RESULT_OPEN_ANONYMISE = "open_anonymise_after_sample"
        const val RESULT_OPEN_DESTINATION = "open_destination_after_sample"
        const val DESTINATION_SAFEGUARD = "safeguard"
        const val DESTINATION_ACCESS = "access"
        private const val ARG_INITIAL_TAB = "mapsafe_initial_tab"
        private const val STATE_ACTIVE_TAB = "mapsafe_active_tab"

        fun forTab(tab: String) = MapSafeMainDialog().apply {
            arguments = Bundle().apply { putString(ARG_INITIAL_TAB, tab) }
        }
    }
}
