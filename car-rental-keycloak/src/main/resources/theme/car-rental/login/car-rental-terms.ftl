<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=false; section>
    <#if section = "header">
        ${msg("carRentalTermsTitle")}
    <#elseif section = "form">
        <p>${msg("carRentalTermsVersion")}: <span id="terms-version">${termsVersion}</span></p>
        <pre id="terms-text" style="white-space: pre-wrap;">${termsText}</pre>
        <form id="car-rental-terms-form" action="${url.loginAction}" method="post">
            <label for="termsAccepted">
                <input id="termsAccepted" name="termsAccepted" type="checkbox" required />
                ${msg("carRentalTermsAcceptance")}
            </label>
            <div class="${properties.kcFormGroupClass!}">
                <button type="submit" name="action" value="accept"
                        class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}">${msg("carRentalTermsContinue")}</button>
                <button type="submit" name="action" value="cancel" formnovalidate
                        class="${properties.kcButtonClass!} ${properties.kcButtonSecondaryClass!}">${msg("carRentalTermsCancel")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
