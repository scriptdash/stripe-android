package com.stripe.android.paymentsheet.forms

import com.stripe.android.paymentsheet.FormElement
import com.stripe.android.paymentsheet.elements.Controller
import com.stripe.android.paymentsheet.specifications.IdentifierSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * This class will take a list of form elements and optional identifiers.
 * [transformFlow] is the only public method and it will transform
 * the list of form elements into a [FormFieldValues].
 */
internal class TransformElementToFormFieldValueFlow(
    val elements: List<FormElement>,
    val optionalIdentifiers: Flow<List<IdentifierSpec>>,
    val showingMandate: Flow<Boolean>,
    val saveForFutureUse: Flow<Boolean>
) {

    // This maps the field type to the controller
    private val idControllerMap = elements
        .filter { it.controller != null }
        .associate { Pair(it.identifier, it.controller!!) }

    private val currentFieldValueMap = combine(
        getCurrentFieldValuePairs(idControllerMap)
    ) {
        it.toMap()
    }

    /**
     * This will return null if any form field values are incomplete, otherwise it is an object
     * representing all the complete, non-optional fields.
     */
    fun transformFlow() = combine(
        currentFieldValueMap,
        optionalIdentifiers,
        showingMandate,
        saveForFutureUse
    ) { idFieldSnapshotMap, optionalIdentifiers, showingMandate, saveForFutureUse ->
        transform(idFieldSnapshotMap, optionalIdentifiers, showingMandate, saveForFutureUse)
    }

    private fun transform(
        idFieldSnapshotMap: Map<IdentifierSpec, FormFieldEntry>,
        optionalIdentifiers: List<IdentifierSpec>,
        showingMandate: Boolean,
        saveForFutureUse: Boolean
    ): FormFieldValues? {
        // This will run twice in a row when the save for future use state changes: once for the
        // saveController changing and once for the the optionalFields changing
        val optionalFilteredFieldSnapshotMap = idFieldSnapshotMap.filter {
            !optionalIdentifiers.contains(it.key)
        }

        return FormFieldValues(
            optionalFilteredFieldSnapshotMap,
            showingMandate,
            saveForFutureUse
        ).takeIf {
            optionalFilteredFieldSnapshotMap.values.map { it.isComplete }
                .none { complete -> !complete }
        }
    }

    private fun getCurrentFieldValuePairs(idControllerMap: Map<IdentifierSpec, Controller>) =
        idControllerMap.map { fieldControllerEntry ->
            getCurrentFieldValuePair(fieldControllerEntry.key, fieldControllerEntry.value)
        }

    private fun getCurrentFieldValuePair(
        field: IdentifierSpec,
        controller: Controller
    ) = combine(controller.rawFieldValue, controller.isComplete) { rawFieldValue, isComplete ->
        Pair(
            field,
            FormFieldEntry(
                value = rawFieldValue,
                isComplete = isComplete,
                type = controller.elementType
            )
        )
    }
}
