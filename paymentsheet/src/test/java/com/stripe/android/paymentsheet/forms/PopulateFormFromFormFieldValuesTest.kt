package com.stripe.android.paymentsheet.forms

import com.google.common.truth.Truth.assertThat
import com.stripe.android.paymentsheet.ElementType
import com.stripe.android.paymentsheet.FormElement
import com.stripe.android.paymentsheet.SectionFieldElement
import com.stripe.android.paymentsheet.elements.EmailConfig
import com.stripe.android.paymentsheet.elements.TextFieldController
import com.stripe.android.paymentsheet.specifications.IdentifierSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PopulateFormFromFormFieldValuesTest {
    private val emailController = TextFieldController(EmailConfig())
    private val emailSection = FormElement.SectionElement(
        identifier = IdentifierSpec("emailSection"),
        SectionFieldElement.Email(
            IdentifierSpec("email"),
            emailController,
            0
        ),
        emailController
    )

    @Test
    fun `Populate form elements from form field values`() {
        runBlocking {
            val formFieldValues = FormFieldValues(
                mapOf(
                    emailSection.identifier to FormFieldEntry(
                        ElementType.Email,
                        "valid@email.com",
                        true
                    )
                ),
                showsMandate = true,
                saveForFutureUse = false
            )
            PopulateFormFromFormFieldValues(
                listOf(emailSection)
            ).populateWith(formFieldValues)

            assertThat(emailController.fieldValue.first())
                .isEqualTo("valid@email.com")
        }
    }

    @Test
    fun `Attempt to populate with a value not in the form`() {
        runBlocking {
            val formFieldValues = FormFieldValues(
                mapOf(
                    IdentifierSpec("not in list form elements") to FormFieldEntry(
                        ElementType.Email,
                        "valid@email.com",
                        true
                    )
                ),
                showsMandate = true,
                saveForFutureUse = false
            )
            PopulateFormFromFormFieldValues(
                listOf(emailSection)
            ).populateWith(formFieldValues)

            assertThat(emailController.fieldValue.first())
                .isEqualTo("")
        }
    }

    @Test
    fun `Attempt to populate form with no values`() {
        runBlocking {
            val formFieldValues = FormFieldValues(
                mapOf(),
                showsMandate = true,
                saveForFutureUse = false
            )
            PopulateFormFromFormFieldValues(
                listOf(emailSection)
            ).populateWith(formFieldValues)

            assertThat(emailController.fieldValue.first())
                .isEqualTo("")
        }
    }
}
