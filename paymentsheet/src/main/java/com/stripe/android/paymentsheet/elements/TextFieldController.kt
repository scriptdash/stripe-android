package com.stripe.android.paymentsheet.elements

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.stripe.android.paymentsheet.ElementType
import com.stripe.android.paymentsheet.elements.TextFieldStateConstants.Error
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * This class will provide the onValueChanged and onFocusChanged functionality to the field's
 * composable.  These functions will update the observables as needed.  It is responsible for
 * exposing immutable observers for its data
 */
internal class TextFieldController @VisibleForTesting constructor(
    private val textFieldConfig: TextFieldConfig,
    override val elementType: ElementType
) : Controller {

    constructor(
        textFieldConfig: TextFieldConfig
    ) : this(
        textFieldConfig,
        textFieldConfig.elementType
    )

    @StringRes
    override val label: Int = textFieldConfig.label
    val debugLabel = textFieldConfig.debugLabel

    /** This is all the information that can be observed on the element */
    private val _fieldValue = MutableStateFlow("")
    override val fieldValue: Flow<String> = _fieldValue

    override val rawFieldValue: Flow<String> = _fieldValue.map { textFieldConfig.convertToRaw(it) }

    private val _fieldState = MutableStateFlow<TextFieldState>(Error.AlwaysError)

    private val _hasFocus = MutableStateFlow(false)

    val visibleError: Flow<Boolean> = combine(_fieldState, _hasFocus) { fieldState, hasFocus ->
        fieldState.shouldShowError(hasFocus)
    }
    override val errorMessage: Flow<Int?> = visibleError.map { visibleError ->
        _fieldState.value.getErrorMessageResId()?.takeIf { visibleError }
    }

    val isFull: Flow<Boolean> = _fieldState.map { it.isFull() }

    override val isComplete: Flow<Boolean> = _fieldState.map { it.isValid() }

    init {
        onValueChange("")
    }

    /**
     * This is called when the value changed to is a display value.
     */
    fun onValueChange(displayFormatted: String) {
        _fieldValue.value = textFieldConfig.filter(displayFormatted)

        // Should be filtered value
        _fieldState.value = textFieldConfig.determineState(_fieldValue.value)
    }

    /**
     * This is called when the value changed to is a raw backing value, not a display value.
     */
    override fun onRawValueChange(rawValue: String) {
        onValueChange(textFieldConfig.convertFromRaw(rawValue))
    }

    fun onFocusChange(newHasFocus: Boolean) {
        _hasFocus.value = newHasFocus
    }
}
