package com.example.demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

@SpringBootApplication
@EnableRetry
public class WebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public CommandLineRunner commandLineRunner(RestTemplate restTemplate) {
        return args -> {
            try {
                // Make initial POST request to generate webhook
                String generateWebhookUrl = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook";
                
                // Create request body
                Map<String, String> requestBody = new HashMap<>();
                requestBody.put("name", "John Doe");
                requestBody.put("regNo", "REG12347");
                requestBody.put("email", "john@example.com");
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
                
                // Make the POST request
                String response = restTemplate.postForObject(generateWebhookUrl, request, String.class);
                System.out.println("Response from webhook generator: " + response);
                
                // Process the response
                ObjectMapper mapper = new ObjectMapper();
                JsonNode responseJson = mapper.readTree(response);
                
                // Add null checks for response fields
                if (responseJson == null) {
                    throw new RuntimeException("Response from generateWebhook is null");
                }
                
                if (!responseJson.has("webhook") || responseJson.get("webhook").isNull()) {
                    throw new RuntimeException("Webhook URL not found in response");
                }
                
                if (!responseJson.has("accessToken") || responseJson.get("accessToken").isNull()) {
                    throw new RuntimeException("Access token not found in response");
                }
                
                if (!responseJson.has("data") || responseJson.get("data").isNull() || 
                    !responseJson.get("data").has("users") || responseJson.get("data").get("users").isNull()) {
                    throw new RuntimeException("Users data not found in response");
                }
                
                String webhook = responseJson.get("webhook").asText();
                String accessToken = responseJson.get("accessToken").asText();
                
                // Fixed: Correctly access the nested users array
                JsonNode usersData = responseJson.get("data").get("users").get("users");
                
                // Extract findId and n values from the response instead of hardcoding
                int findId = responseJson.get("data").get("users").get("findId").asInt();
                int n = responseJson.get("data").get("users").get("n").asInt();
                
                System.out.println("Webhook URL: " + webhook);
                System.out.println("Access Token: " + accessToken);
                System.out.println("Users Data: " + usersData);
                System.out.println("Find ID: " + findId + ", N: " + n);
                
                // Solve the Nth-Level Followers problem
                List<Integer> nthLevelFollowers = findNthLevelFollowers(usersData, findId, n);
                System.out.println("Nth level followers found: " + nthLevelFollowers);
                
                // Post the solution to the webhook URL with retry
                postSolutionToWebhook(restTemplate, webhook, accessToken, nthLevelFollowers);
                
                System.out.println("Solution posted to webhook: " + webhook);
            } catch (Exception e) {
                System.err.println("Error occurred: " + e.getMessage());
                e.printStackTrace();
            }
        };
    }
    
    private List<Integer> findNthLevelFollowers(JsonNode usersData, int findId, int nthLevel) {
        // Create a map of user ID to their follows list
        Map<Integer, List<Integer>> followsMap = new HashMap<>();
        
        // Check if usersData is null or empty
        if (usersData == null || !usersData.isArray() || usersData.size() == 0) {
            System.out.println("Users data is null, empty, or not an array");
            return new ArrayList<>();
        }
        
        for (JsonNode user : usersData) {
            // Check if user has 'id' field
            if (!user.has("id") || user.get("id").isNull()) {
                System.out.println("Found user without ID, skipping: " + user);
                continue;
            }
            
            int userId = user.get("id").asInt();
            List<Integer> follows = new ArrayList<>();
            
            // Check for follows array with better null handling
            if (user.has("follows") && !user.get("follows").isNull() && user.get("follows").isArray()) {
                for (JsonNode followId : user.get("follows")) {
                    // Check if the followId is valid
                    if (followId != null && !followId.isNull()) {
                        follows.add(followId.asInt());
                    }
                }
            }
            
            followsMap.put(userId, follows);
        }
        
        System.out.println("User follows map created: " + followsMap);
        
        // If the user doesn't exist or the level is 0, return an empty list
        if (!followsMap.containsKey(findId) || nthLevel == 0) {
            System.out.println("User ID " + findId + " not found in map or level is 0, returning empty list");
            return new ArrayList<>();
        }
        
        // Find users at exact nth level using BFS
        Set<Integer> visited = new HashSet<>();
        visited.add(findId);
        
        List<Integer> currentLevel = new ArrayList<>();
        currentLevel.add(findId);
        
        int currentDistance = 0;
        
        // Process each level
        while (!currentLevel.isEmpty() && currentDistance < nthLevel) {
            Set<Integer> nextLevelSet = new HashSet<>();  // Use a set to avoid duplicates
            
            for (int userId : currentLevel) {
                for (int followId : followsMap.getOrDefault(userId, Collections.emptyList())) {
                    if (!visited.contains(followId)) {
                        visited.add(followId);
                        nextLevelSet.add(followId);
                    }
                }
            }
            
            currentLevel = new ArrayList<>(nextLevelSet);
            currentDistance++;
            System.out.println("Level " + currentDistance + " followers: " + currentLevel);
        }
        
        // The currentLevel now contains all users at the exact nth level
        return currentLevel;
    }
    
    @Retryable(
        value = { HttpServerErrorException.class },
        maxAttempts = 4,
        backoff = @Backoff(delay = 1000)
    )
    public void postSolutionToWebhook(RestTemplate restTemplate, String webhook, String accessToken, List<Integer> outcome) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Try with raw token (no Bearer prefix)
        headers.set("Authorization", accessToken);
        
        Map<String, Object> responseBody = new HashMap<>();
        
        // Format outcome as a nested array [[4, 5]] instead of [4, 5]
        List<List<Integer>> nestedOutcome = new ArrayList<>();
        nestedOutcome.add(outcome);
        responseBody.put("outcome", nestedOutcome);
        
        System.out.println("Posting to webhook with payload: " + responseBody);
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(responseBody, headers);
        
        try {
            String result = restTemplate.postForObject(webhook, request, String.class);
            System.out.println("Webhook response: " + result);
        } catch (HttpServerErrorException e) {
            System.out.println("Server error when posting to webhook, will retry: " + e.getMessage());
            throw e; // Rethrow to trigger retry
        } catch (Exception e) {
            System.out.println("Error when posting to webhook: " + e.getMessage());
            throw e;
        }
    }
}
