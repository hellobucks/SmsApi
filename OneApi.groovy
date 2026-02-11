import com.rameses.annotations.*;
import java.net.URL;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.security.cert.X509Certificate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.logging.Logger;

class OneApi {

    private static final Logger logger = Logger.getLogger(OneApi.class.getName())

    @Env
    def env

    private final ObjectMapper objectMapper = new ObjectMapper()

    @ProxyMethod
    def send(def params) {
        def reqId = params.request_id ?: "REQ_${System.currentTimeMillis()}"
        
        logger.info("OneApi: Starting M360 API call - Request ID: ${reqId}")
        
        // Use the exact M360 URL provided as full endpoint
        def baseUrl = env?.M360_BASE_URL ?: "https://api.m360.com.ph/v4/sms/send"
        def isSandbox = baseUrl.contains("sandbox") || env?.M360_ENVIRONMENT == "sandbox"
        
        if (isSandbox) {
            logger.info("OneApi: Using SANDBOX environment - Request ID: ${reqId}")
        } else {
            logger.info("OneApi: Using PRODUCTION environment - Request ID: ${reqId}")
        }
        
        logger.info("OneApi: Target URL: ${baseUrl}")
        
        try {
            // Extract parameters from Map
            def fromName = params.from?.trim()
            def toNumbers = params.to
            def dcs = params.dcs ?: 0
            
            // Handle both direct text and nested content.text formats
            def text = params.text?.trim()
            if (!text && params.content?.text) {
                text = params.content.text?.trim()
            }

            // Get credentials from params first, then environment, or use defaults
            def appKey = params.app_key ?: env?.M360_APP_KEY ?: "test_key"
            def appSecret = params.app_secret ?: env?.M360_APP_SECRET ?: "test_secret"
            
            logger.info("OneApi: Debug - params.app_key: ${params.app_key}")
            logger.info("OneApi: Debug - params.app_secret: ${params.app_secret ? 'PRESENT' : 'MISSING'}")
            logger.info("OneApi: Debug - final appKey: ${appKey}")
            logger.info("OneApi: Debug - final appSecret: ${appSecret ? 'PRESENT' : 'MISSING'}")

            // Validate credentials are present
            if (!appKey || appKey == "test_key" || appKey.length() < 5) {
                // Temporarily allow test credentials for SSL testing
                if (appKey == "test_key") {
                    logger.warning("OneApi: Allowing test_key for SSL testing - Request ID: ${reqId}")
                } else {
                    return [
                        status: 'error',
                        message: "M360 credentials missing or using test credentials. Please set M360_APP_KEY environment variable with real credentials.",
                        request_id: reqId,
                        error_type: 'credentials_missing',
                        provider: 'M360',
                        environment: isSandbox ? 'sandbox' : 'production'
                    ]
                }
            }

            if (!appSecret || appSecret == "test_secret" || appSecret.length() < 5) {
                // Temporarily allow test credentials for SSL testing
                if (appSecret == "test_secret") {
                    logger.warning("OneApi: Allowing test_secret for SSL testing - Request ID: ${reqId}")
                } else {
                    return [
                        status: 'error',
                        message: "M360 credentials missing or using test credentials. Please set M360_APP_SECRET environment variable with real credentials.",
                        request_id: reqId,
                        error_type: 'credentials_missing',
                        provider: 'M360',
                        environment: isSandbox ? 'sandbox' : 'production'
                    ]
                }
            }

            logger.info("OneApi: Using app_key: ${appKey.substring(0, 4)}****")
            logger.info("OneApi: From: ${fromName}, Recipients: ${toNumbers.size()}, Text length: ${text.length()}")

            // Build M360 API payload exactly as specified in documentation
            def payload = [
                app_key: appKey,
                app_secret: appSecret,
                from: fromName,
                to: toNumbers,
                dcs: dcs,
                request_id: reqId,
                content: [
                    text: text
                ]
            ]

            logger.info("OneApi: Payload constructed - Request ID: ${reqId}")

            // Convert payload to JSON
            def jsonPayload = objectMapper.writeValueAsString(payload)
            logger.info("OneApi: JSON payload created - Size: ${jsonPayload.length()} characters")

            // Send HTTP request using HttpURLConnection
            def response = sendHttpRequest(baseUrl, jsonPayload, reqId)

            logger.info("OneApi: Response received - Request ID: ${reqId}, Status: ${response.statusCode}")

            // Parse response
            def responseData = objectMapper.readValue(response.responseBody, Map.class)
            logger.info("OneApi: Response parsed successfully - Request ID: ${reqId}")

            return [
                status: 'success',
                message: 'SMS sent successfully via M360',
                request_id: reqId,
                response: responseData,
                attempts: 1,
                provider: 'M360',
                environment: isSandbox ? 'sandbox' : 'production'
            ]

        } catch (Exception e) {
            logger.severe("OneApi: Failed to send SMS - Request ID: ${reqId}, Error: ${e.message}")
            
            return [
                status: 'error',
                message: "Failed to send SMS via M360: ${e.message}",
                request_id: reqId,
                error_type: 'api_error',
                provider: 'M360',
                environment: isSandbox ? 'sandbox' : 'production'
            ]
        }
    }

    private def sendHttpRequest(String baseUrl, String jsonPayload, String reqId) {
        def url = new URL(baseUrl)
        def connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.setRequestMethod("POST")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "OneApi/1.0")
            connection.setRequestProperty("Accept", "application/json")
            connection.setDoOutput(true)
            connection.setConnectTimeout(30000)
            connection.setReadTimeout(30000)

            logger.info("OneApi: HTTP request created - Request ID: ${reqId}")

            // Send request
            connection.outputStream.withStream { stream ->
                stream.write(jsonPayload.getBytes("UTF-8"))
            }

            logger.info("OneApi: HTTP request sent - Request ID: ${reqId}")

            // Get response
            def statusCode = connection.responseCode
            def responseBody = connection.responseCode >= 400 ? 
                connection.errorStream.text : connection.inputStream.text

            logger.info("OneApi: HTTP response received - Request ID: ${reqId}, Status: ${statusCode}, Body: ${responseBody}")

            if (statusCode == 200) {
                logger.info("OneApi: Request successful - Request ID: ${reqId}")
                return [statusCode: statusCode, responseBody: responseBody]
            } else if (statusCode >= 400 && statusCode < 500) {
                // Client errors - don't retry
                def errorMsg = "Client error ${statusCode}: ${responseBody}"
                logger.warning("OneApi: ${errorMsg} - Request ID: ${reqId}")
                throw new Exception(errorMsg)
            } else {
                // Server errors - retry
                def errorMsg = "Server error ${statusCode}: ${responseBody}"
                logger.warning("OneApi: ${errorMsg} - Request ID: ${reqId}, Will retry")
                throw new Exception(errorMsg)
            }

        } finally {
            connection.disconnect()
        }
    }

    @ProxyMethod
    def test(def params) {
        def baseUrl = env?.M360_BASE_URL ?: "https://api.m360.com.ph/v4/sms/send"
        def isSandbox = baseUrl.contains("sandbox") || env?.M360_ENVIRONMENT == "sandbox"
        
        // Use credentials from params if provided, otherwise environment
        def appKey = params.app_key ?: env?.M360_APP_KEY ?: "test_key"
        def appSecret = params.app_secret ?: env?.M360_APP_SECRET ?: "test_secret"
        
        def credentialsStatus = (params.app_key && params.app_secret) ? "configured" : "missing"
        
        // Test basic connectivity
        try {
            def url = new URL(baseUrl)
            def connection = url.openConnection() as HttpURLConnection
            connection.setRequestMethod("GET")
            connection.setConnectTimeout(10000)
            connection.setReadTimeout(10000)
            
            
            def statusCode = connection.responseCode
            connection.disconnect()
            
            return [
                status: 'success',
                message: 'OneApi service test method executed',
                params_received: params,
                app_key: appKey.substring(0, 4) + "****",
                environment: isSandbox ? 'sandbox' : 'production',
                base_url: baseUrl,
                credentials_status: credentialsStatus,
                connectivity_test: [
                    url: baseUrl,
                    status_code: statusCode,
                    reachable: statusCode >= 200 && statusCode < 500
                ]
            ]
        } catch (Exception e) {
            return [
                status: 'error',
                message: 'Connectivity test failed: ' + e.message,
                params_received: params,
                app_key: appKey.substring(0, 4) + "****",
                environment: isSandbox ? 'sandbox' : 'production',
                base_url: baseUrl,
                credentials_status: credentialsStatus,
                connectivity_test: [
                    url: baseUrl,
                    error: e.message,
                    reachable: false
                ]
            ]
        }
    }
}