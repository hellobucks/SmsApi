import com.rameses.annotations.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

class SMSApiService {

    private static final Logger logger = Logger.getLogger(SMSApiService.class.getName())
    
    // Globe Telecom mobile number pattern (PH mobile numbers)
    private static final Pattern MOBILE_PATTERN = Pattern.compile(/^09[0-9]{9}$/)
    
    @Service("OneApi")
    def oneApiService

    @ProxyMethod
    def sendMessage(def params) {
        def requestId = params.request_id ?: "REQ_${System.currentTimeMillis()}"
        
        logger.info("Starting SMS dispatch - Request ID: ${requestId}")
        logger.info("SMS Parameters - From: ${params.from}, To: ${params.to}, Text length: ${params.text?.length()}")
      
        try {
            // Comprehensive validation
            def validationResult = validateSMSParams(params)
            if (!validationResult.valid) {
                logger.warning("Validation failed - Request ID: ${requestId}, Errors: ${validationResult.errors}")
                return [
                    status: 'error',
                    message: "Validation failed: ${validationResult.errors.join(', ')}",
                    request_id: requestId,
                    validation_errors: validationResult.errors
                ]
            }

            // Extract validated parameters
            def fromName = params.from?.trim()
            def toNumbers = params.to
            
            // Handle both direct text and nested content.text formats
            def text = params.text?.trim()
            if (!text && params.content?.text) {
                text = params.content.text?.trim()
            }

            // Additional business logic validation
            def businessValidation = validateBusinessRules(fromName, toNumbers, text)
            if (!businessValidation.valid) {
                logger.warning("Business validation failed - Request ID: ${requestId}, Errors: ${businessValidation.errors}")
                return [
                    status: 'error',
                    message: "Business validation failed: ${businessValidation.errors.join(', ')}",
                    request_id: requestId,
                    validation_errors: businessValidation.errors
                ]
            }

            logger.info("Validation passed - Request ID: ${requestId}, Recipients: ${toNumbers.size()}")

            // Prepare payload for OneApi service
            def oneApiParams = [
                from: fromName,
                to: toNumbers,
                request_id: requestId
            ]
            
            // Handle content format for OneApi
            if (params.content?.text) {
                oneApiParams.content = [text: params.content.text]
            } else {
                oneApiParams.content = [text: text]
            }
            
            // Forward credentials if provided in params
            if (params.app_key) oneApiParams.app_key = params.app_key
            if (params.app_secret) oneApiParams.app_secret = params.app_secret
            if (params.dcs != null) oneApiParams.dcs = params.dcs

            logger.info("SMSApiService: Debug - params.app_key: ${params.app_key}")
            logger.info("SMSApiService: Debug - params.app_secret: ${params.app_secret ? 'PRESENT' : 'MISSING'}")
            logger.info("SMSApiService: Debug - oneApiParams.app_key: ${oneApiParams.app_key}")
            logger.info("SMSApiService: Debug - oneApiParams.app_secret: ${oneApiParams.app_secret ? 'PRESENT' : 'MISSING'}")

            logger.info("Calling OneApi service - Request ID: ${requestId}")
            
            // Call OneApi service
            def response = oneApiService.send(oneApiParams)

            logger.info("OneApi response received - Request ID: ${requestId}, Status: ${response.status}")
            
            return response

        } catch (Exception e) {
            logger.severe("SMS dispatch failed - Request ID: ${requestId}, Error: ${e.message}")
            e.printStackTrace()
            
            return [
                status: 'error',
                message: "SMS dispatch failed: ${e.message}",
                request_id: requestId,
                error_type: 'system_error'
            ]
        }
    }

    private def validateSMSParams(def params) {
        def errors = []
        
        // Validate required fields
        if (!params.from?.trim()) {
            errors.add("Sender name (from) is required")
        }
        
        if (!params.to || !(params.to instanceof List) || params.to.isEmpty()) {
            errors.add("Recipient list (to) is required and must be a non-empty list")
        }
        
        // Handle both direct text and nested content.text formats
        def textContent = params.text?.trim()
        if (!textContent && params.content?.text) {
            textContent = params.content.text?.trim()
        }
        
        if (!textContent) {
            errors.add("SMS content (text) is required")
        }
        
        // Validate mobile numbers
        if (params.to) {
            params.to.eachWithIndex { number, index ->
                if (!number || !MOBILE_PATTERN.matcher(number.toString().trim()).matches()) {
                    errors.add("Invalid mobile number at index ${index}: ${number}. Must be 11-digit PH number starting with 09")
                }
            }
        }
        
        // Validate text length (SMS limit is typically 160 characters for GSM-7)
        if (params.text && params.text.length() > 160) {
            errors.add("SMS text exceeds 160 characters limit (${params.text.length()} characters)")
        }
        
        // Validate sender name length
        if (params.from && params.from.length() > 11) {
            errors.add("Sender name exceeds 11 characters limit (${params.from.length()} characters)")
        }
        
        return [
            valid: errors.isEmpty(),
            errors: errors
        ]
    }
    
    private def validateBusinessRules(def fromName, def toNumbers, def text) {
        def errors = []
        
        // Check for duplicate recipients
        def uniqueNumbers = toNumbers.collect { it.toString().trim() }.unique()
        if (uniqueNumbers.size() != toNumbers.size()) {
            errors.add("Duplicate mobile numbers found in recipient list")
        }
        
        // Check for spam-like content (basic checks)
        def spamKeywords = ['FREE', 'WIN', 'PRIZE', 'URGENT', 'CLICK', 'CONGRATULATIONS']
        def upperText = text.toUpperCase()
        def spamCount = spamKeywords.count { upperText.contains(it) }
        if (spamCount >= 3) {
            errors.add("Message contains multiple spam-like keywords")
        }
        
        // Check for excessive special characters
        def specialCharCount = 0
        for (int i = 0; i < text.length(); i++) {
            def c = text.charAt(i)
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ')) {
                specialCharCount++
            }
        }
        if (specialCharCount > text.length() * 0.3) {
            errors.add("Message contains too many special characters")
        }
        
        return [
            valid: errors.isEmpty(),
            errors: errors
        ]
    }

    @ProxyMethod
    def test(def params) {
        // Test method for basic functionality
        return [
            status: 'success',
            message: 'SMSApiService test method executed',
            params_received: params
        ]
    }
}
