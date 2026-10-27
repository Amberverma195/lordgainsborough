package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.OrderReview;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private final CustomerOrderRepository orderRepository = mock(CustomerOrderRepository.class);
    private final OrderReviewRepository reviewRepository = mock(OrderReviewRepository.class);
    private final OrderRealtimeService realtimeService = mock(OrderRealtimeService.class);
    private final OrderAccessService orderAccessService = mock(OrderAccessService.class);
    private final ReviewController controller = new ReviewController(orderRepository, reviewRepository, realtimeService, orderAccessService);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(orderRepository, reviewRepository, realtimeService, orderAccessService);
        when(orderAccessService.canAccess(any(), any(), any())).thenReturn(true);
    }

    @Test
    void createReviewMarksOrderReviewedAndSavesOnlyReviewedItems() {
        CustomerOrder order = orderWithItem();
        OrderReview request = reviewRequest(4, "Great crust");

        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(reviewRepository.existsByOrderId("order-1")).thenReturn(false);
        when(reviewRepository.save(any(OrderReview.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<OrderReview> response = controller.createReview("order-1", null, null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getItems().size());
        assertEquals(4, response.getBody().getItems().get(0).getRating());
        assertEquals("Great crust", response.getBody().getItems().get(0).getDescription());

        ArgumentCaptor<CustomerOrder> orderCaptor = ArgumentCaptor.forClass(CustomerOrder.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertTrue(orderCaptor.getValue().isReviewed());
        verify(realtimeService).orderUpdated(orderCaptor.getValue());
    }

    @Test
    void createReviewRejectsDuplicateReview() {
        CustomerOrder order = orderWithItem();
        OrderReview request = reviewRequest(5, "");

        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(reviewRepository.existsByOrderId("order-1")).thenReturn(true);

        ResponseEntity<OrderReview> response = controller.createReview("order-1", null, null, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(reviewRepository, never()).save(any(OrderReview.class));
        verify(orderRepository, never()).save(any(CustomerOrder.class));
    }

    @Test
    void createReviewAllowsTextOnlyReviewWithoutForcingStarRating() {
        CustomerOrder order = orderWithItem();
        OrderReview request = reviewRequest(0, "Loved the sauce");

        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(reviewRepository.existsByOrderId("order-1")).thenReturn(false);
        when(reviewRepository.save(any(OrderReview.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<OrderReview> response = controller.createReview("order-1", null, null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getItems().get(0).getRating());
        assertEquals("Loved the sauce", response.getBody().getItems().get(0).getDescription());
    }

    private CustomerOrder orderWithItem() {
        CustomerOrder order = new CustomerOrder();
        order.setId("order-1");
        order.setCustomerName("Amber Verma");
        order.setCustomerEmail("amber@example.com");
        order.setCustomerPhone("+1 (519)-614-3615");

        CustomerOrder.OrderItem item = new CustomerOrder.OrderItem();
        item.setMenuItemId("pizza-1");
        item.setName("Pepperoni Pizza");
        item.setQuantity(1);
        order.setItems(List.of(item));
        return order;
    }

    private OrderReview reviewRequest(int rating, String description) {
        OrderReview review = new OrderReview();
        OrderReview.ReviewItem item = new OrderReview.ReviewItem();
        item.setMenuItemId("pizza-1");
        item.setItemName("Pepperoni Pizza");
        item.setRating(rating);
        item.setDescription(description);
        review.setItems(List.of(item));
        return review;
    }

    @Test void nullItemsAndBlankIdsAreIgnoredAndDescriptionsTrimmed() {
        var order=orderWithItem();var request=reviewRequest(99,"  good  ");var blank=new OrderReview.ReviewItem();blank.setMenuItemId(" ");blank.setRating(5);
        request.setItems(java.util.Arrays.asList(null,blank,request.getItems().getFirst()));
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));when(reviewRepository.save(any())).thenAnswer(call->call.getArgument(0));when(orderRepository.save(any())).thenAnswer(call->call.getArgument(0));
        var result=controller.createReview("order-1",null,null,request).getBody();assertEquals(1,result.getItems().size());assertEquals(5,result.getItems().getFirst().getRating());assertEquals("good",result.getItems().getFirst().getDescription());
    }
    @Test void unauthorizedMongoReviewUsesLegacyAccessAndReturns404WithoutSave() {
        var order=orderWithItem();when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));when(orderAccessService.canAccess(order,"Bearer jwt","token")).thenReturn(false);
        assertEquals(404,controller.createReview("order-1","Bearer jwt","token",reviewRequest(5,"")).getStatusCode().value());verify(orderAccessService).canAccess(order,"Bearer jwt","token");verify(reviewRepository,never()).save(any());
    }
    @Test void moderationHandlesNullStatusAndNormalizesCaseWithoutChangingItems() {
        assertEquals(400,controller.updateReviewStatus("id",java.util.Collections.singletonMap("status",null)).getStatusCode().value());
        var review=reviewRequest(4,"keep");when(reviewRepository.findById("id")).thenReturn(Optional.of(review));when(reviewRepository.save(review)).thenReturn(review);
        assertEquals("PUBLISHED",controller.updateReviewStatus("id",java.util.Map.of("status"," published ")).getBody().getStatus());assertEquals("keep",review.getItems().getFirst().getDescription());
    }
}
