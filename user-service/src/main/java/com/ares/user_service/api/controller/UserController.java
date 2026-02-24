package com.ares.user_service.api.controller;

import com.ares.user_service.api.request.ChangePasswordRequest;
import com.ares.user_service.api.request.CreateUserRequest;
import com.ares.user_service.api.request.UpdateUserRequest;
import com.ares.user_service.api.response.UserResponse;
import com.ares.user_service.application.service.UserApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserApplicationService userApplicationService;

    //GET All User
    @GetMapping
    public Page<UserResponse> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role
    ) {
        return userApplicationService.getAllUsers(page, size, sort, status, role);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable String userId) {
        return userApplicationService.getUserById(userId);
    }



    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest request){
        UserResponse response = userApplicationService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUserById(
            @PathVariable String userId,
            @RequestBody UpdateUserRequest request
    ) {
        return userApplicationService.updateUserById(userId, request);
    }

    @PutMapping("/{userId}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable String userId,
            @RequestBody ChangePasswordRequest request
    ) {
        return userApplicationService.changePassword(userId, request);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUserById(@PathVariable String userId) {
        return userApplicationService.deleteUserById(userId);
    }


}
