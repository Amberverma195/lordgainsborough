
package com.lordgainsborough.restaurant.controller;
import com.lordgainsborough.restaurant.model.*;
import com.lordgainsborough.restaurant.service.*;
import com.lordgainsborough.restaurant.dto.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PostgresReviewControllerIntegrationTest extends PostgresPg020IntegrationSupport {
    private OrderReview submit(OrderCreationResponse order) {
        var response=reviewController.createReview(order.getOrder().getId(),null,order.getTrackingToken(),requestReview());
        assertThat(response.getStatusCode().value()).isEqualTo(200);return response.getBody();
    }
    @Test void completeHttpFlowPreservesSafeJsonStateItemsAndModeration() throws Exception {
        var order=createOrder();String id=order.getOrder().getId();reset(sockets);clearInvocations(mongoUsers);
        var request=requestReview();request.setId("forged");request.setOrderId("other");request.setCustomerName("forged");request.setCustomerEmail("forged");request.setCustomerPhone("forged");request.setStatus("PUBLISHED");request.setCreatedAt(java.time.LocalDateTime.MIN);request.setUpdatedAt(java.time.LocalDateTime.MIN);
        String body=mvc.perform(post("/api/orders/"+id+"/reviews").header(OrderAccessService.TRACKING_TOKEN_HEADER,order.getTrackingToken()).contentType("application/json").content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.customerName").value("Guest"))
            .andExpect(jsonPath("$.items[0].itemName").value("Steak")).andReturn().getResponse().getContentAsString();
        var node=json.readTree(body);assertThat(node.size()).isEqualTo(9);assertThat(node.get("items").get(0).size()).isEqualTo(4);
        assertThat(node.get("id").asText()).isNotEqualTo("forged");assertThat(node.get("orderId").asText()).isEqualTo(id);
        assertThat(body).doesNotContain("trackingToken","trackingTokenHash","itemIndex","hibernateLazyInitializer");
        assertThat(count("order_reviews")).isEqualTo(1);assertThat(count("order_review_items")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reviewed FROM customer_orders WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT item_index FROM order_review_items",Integer.class)).isZero();
        mvc.perform(get("/api/orders/"+id).header(OrderAccessService.TRACKING_TOKEN_HEADER,order.getTrackingToken())).andExpect(status().isOk()).andExpect(jsonPath("$.reviewed").value(true));
        assertThat(kds.getAllOrders().getFirst().isReviewed()).isTrue();
        assertThat(reviewController.createReview(id,null,order.getTrackingToken(),request).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(mongoUsers);
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent(eq("order.updated"),any(CustomerOrder.class));
        verify(sockets.getRoomOperations("order:"+id),times(1)).sendEvent(eq("order.updated"),any(CustomerOrder.class));
        var payload=org.mockito.ArgumentCaptor.forClass(CustomerOrder.class);verify(sockets.getRoomOperations("kds")).sendEvent(eq("order.updated"),payload.capture());
        assertThat(payload.getValue().isReviewed()).isTrue();assertThat(payload.getValue().getCustomerOrderCount()).isEqualTo(1);
        assertThat(json.writeValueAsString(payload.getValue())).doesNotContain("trackingTokenHash");
        mvc.perform(get("/api/kds/reviews").header("Authorization","Bearer admin-fixture")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(node.get("id").asText()));
        mvc.perform(patch("/api/kds/reviews/"+node.get("id").asText()+"/status").header("Authorization","Bearer admin-fixture").contentType("application/json").content(json.writeValueAsString(Map.of("status"," published "))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong","none","jwt","cross"})
    void missingAndUnauthorizedIndistinguishableWithoutMongoIdentityLookup(String kind) throws Exception {
        var order=createOrder();String id=kind.equals("missing")?"absent":order.getOrder().getId();String token=null;
        if(kind.equals("wrong"))token=access.generateTrackingToken();if(kind.equals("cross"))token=createOrder().getTrackingToken();
        var request=post("/api/orders/"+id+"/reviews").contentType("application/json").content(json.writeValueAsString(requestReview()));
        if(token!=null)request.header(OrderAccessService.TRACKING_TOKEN_HEADER,token);if(kind.equals("jwt"))request.header("Authorization","Bearer admin-fixture");
        clearInvocations(mongoUsers);mvc.perform(request).andExpect(status().isNotFound()).andExpect(content().string(""));verifyNoInteractions(mongoUsers);
        assertThat(count("order_reviews")).isZero();
    }
    @Test void legacyNormalizationPreservesZeroRatingsDuplicatesFallbackAndClientOrder() {
        var order=createOrder();var request=requestReview();var first=request.getItems().getFirst();first.setRating(-99);first.setDescription(" text only ");
        var extra=new OrderReview.ReviewItem();extra.setMenuItemId("not-purchased");extra.setRating(99);
        var blank=new OrderReview.ReviewItem();blank.setMenuItemId(" ");blank.setRating(5);
        request.setItems(Arrays.asList(null,blank,first,extra,first));
        var saved=reviewController.createReview(order.getOrder().getId(),null,order.getTrackingToken(),request).getBody();
        assertThat(saved.getItems()).extracting(OrderReview.ReviewItem::getMenuItemId).containsExactly("dish","not-purchased","dish");
        assertThat(saved.getItems()).extracting(OrderReview.ReviewItem::getRating).containsExactly(0,5,0);
        assertThat(saved.getItems()).extracting(OrderReview.ReviewItem::getItemName).containsExactly("Steak","Item","Steak");
        assertThat(saved.getItems().getFirst().getDescription()).isEqualTo("text only");
        assertThat(jdbc.queryForList("SELECT rating FROM order_review_items ORDER BY item_index",Integer.class)).containsExactly(0,5,0);
    }
    @ParameterizedTest @ValueSource(strings={"empty","null-list","null-item","blank-id","no-content"})
    void invalidOrEmptyItemsReturn400WithoutWrites(String kind) {
        var order=createOrder();var request=requestReview();
        switch(kind) {case "empty" -> request.setItems(List.of());case "null-list" -> request.setItems(null);case "null-item" -> request.setItems(Arrays.asList((OrderReview.ReviewItem)null));
        case "blank-id" -> request.getItems().getFirst().setMenuItemId(" ");case "no-content" -> {request.getItems().getFirst().setRating(-1);request.getItems().getFirst().setDescription(" ");}}
        assertThat(reviewController.createReview(order.getOrder().getId(),null,order.getTrackingToken(),request).getStatusCode().value()).isEqualTo(400);assertThat(count("order_reviews")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"long-id","long-name","nul","surrogate"})
    void postgresIncompatibleContentReturns400BeforeWrite(String kind) throws Exception {
        var order=createOrder();var request=requestReview();var item=request.getItems().getFirst();
        switch(kind) {case "long-id" -> item.setMenuItemId("x".repeat(65));case "long-name" -> {item.setMenuItemId("other");item.setItemName("x".repeat(256));}
        case "nul" -> item.setDescription("bad"+(char)0);case "surrogate" -> item.setDescription(String.valueOf((char)0xD800));}
        mvc.perform(post("/api/orders/"+order.getOrder().getId()+"/reviews").header(OrderAccessService.TRACKING_TOKEN_HEADER,order.getTrackingToken()).contentType("application/json").content(json.writeValueAsString(request).replace(String.valueOf((char)0xD800),"\\uD800"))).andExpect(status().isBadRequest());
        assertThat(count("order_reviews")).isZero();assertThat(checkout.findById(order.getOrder().getId()).orElseThrow().isReviewed()).isFalse();
    }
    @Test void simultaneousReviewsCommitExactlyOneAndConflictOtherWithoutDuplicateEvents() throws Exception {
        var order=createOrder();reset(sockets);var statuses=new ConcurrentLinkedQueue<Integer>();
        Runnable action=()->statuses.add(reviewController.createReview(order.getOrder().getId(),null,order.getTrackingToken(),requestReview()).getStatusCode().value());race(action,action);
        assertThat(statuses).containsExactlyInAnyOrder(200,409);assertThat(count("order_reviews")).isEqualTo(1);assertThat(count("order_review_items")).isEqualTo(1);
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent(eq("order.updated"),any(CustomerOrder.class));
    }
    @ParameterizedTest @ValueSource(strings={"order_review_items","customer_orders"})
    void childInsertOrParentUpdateFailureRollsBackBothAndEmitsNothing(String table) {
        var order=createOrder();reset(sockets);
        failureTrigger(table,table.equals("customer_orders")?"UPDATE":"INSERT",()->{
            assertThatThrownBy(()->submit(order)).isInstanceOf(RuntimeException.class);assertThat(count("order_reviews")).isZero();assertThat(count("order_review_items")).isZero();
            assertThat(checkout.findById(order.getOrder().getId()).orElseThrow().isReviewed()).isFalse();verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());
        });
    }
    @Test void reviewAndKitchenOrChatWritersKeepStatusTimestampsPricesAndChildren() throws Exception {
        var order=createOrder();String id=order.getOrder().getId();var before=checkout.findById(id).orElseThrow();
        race(()->submit(order),()->kds.updateStatus(id,Map.of("status","READY")));
        var saved=checkout.findById(id).orElseThrow();assertThat(saved.isReviewed()).isTrue();assertThat(saved.getStatus()).isEqualTo("READY");assertThat(saved.getReadyAt()).isNotNull();
        var second=createOrder();String sid=second.getOrder().getId();
        race(()->submit(second),()->orderController.addCustomerMessage(sid,null,second.getTrackingToken(),Map.of("text","keep")));
        var chatted=checkout.findById(sid).orElseThrow();assertThat(chatted.isReviewed()).isTrue();assertThat(chatted.getMessages()).extracting(CustomerOrder.OrderMessage::getText).containsExactly("keep");
        assertThat(saved.getTotal()).isEqualTo(before.getTotal());assertThat(saved.getTrackingTokenHash()).isEqualTo(before.getTrackingTokenHash());assertThat(saved.getItems()).hasSize(1);
    }
    @Test void uniquenessRemainsDatabaseBoundaryAndRecordPresenceRejectsStaleFlag() {
        var order=createOrder();var review=submit(order);String id=order.getOrder().getId();
        jdbc.update("UPDATE customer_orders SET reviewed=false WHERE id=?",id);
        assertThat(reviewController.createReview(id,null,order.getTrackingToken(),requestReview()).getStatusCode().value()).isEqualTo(409);
        assertThat(orderController.getOrder(id,null,order.getTrackingToken()).getBody().isReviewed()).isTrue();
        assertThatThrownBy(()->jdbc.update("INSERT INTO order_reviews(id,order_id,customer_name,customer_email,customer_phone,status,created_at,updated_at) SELECT 'duplicate',order_id,customer_name,customer_email,customer_phone,status,created_at,updated_at FROM order_reviews WHERE id=?",review.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("order_reviews")).isEqualTo(1);
    }
    @Test void moderationListsOrderedChildrenWithoutNPlusOneAndChangesOnlyModerationFields() {
        var first=submit(createOrder());var second=submit(createOrder());
        jdbc.update("UPDATE order_reviews SET created_at='2026-01-01' WHERE id=?",first.getId());jdbc.update("UPDATE order_reviews SET created_at='2026-02-01' WHERE id=?",second.getId());
        var stats=context.getBean(EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.setStatisticsEnabled(true);stats.clear();
        assertThat(reviewController.getAllReviews()).extracting(OrderReview::getId).containsExactly(second.getId(),first.getId());assertThat(stats.getPrepareStatementCount()).isEqualTo(1);stats.setStatisticsEnabled(false);
        var original=reviewController.getAllReviews().getFirst();
        for(String status:List.of(" pending ","published","HIDDEN")) {
            var response=reviewController.updateReviewStatus(second.getId(),Map.of("status",status));assertThat(response.getStatusCode().value()).isEqualTo(200);
            var changed=response.getBody();assertThat(changed.getUpdatedAt()).isAfterOrEqualTo(original.getUpdatedAt());assertThat(changed.getCustomerEmail()).isEqualTo(original.getCustomerEmail());
            assertThat(changed.getItems()).usingRecursiveComparison().isEqualTo(original.getItems());assertThat(changed.getCreatedAt()).isEqualTo(original.getCreatedAt());
        }
        assertThat(count("order_reviews")).isEqualTo(2);
        assertThat(reviewController.updateReviewStatus(second.getId(),Map.of("status","bad")).getStatusCode().value()).isEqualTo(400);
        assertThat(reviewController.updateReviewStatus(second.getId(),Collections.singletonMap("status",null)).getStatusCode().value()).isEqualTo(400);
        assertThat(reviewController.updateReviewStatus("missing",Map.of("status","HIDDEN")).getStatusCode().value()).isEqualTo(404);
    }
    @Test void adminEndpointsRemainHiddenFromPublicInvalidAndMemberIdentities() throws Exception {
        mvc.perform(get("/api/kds/reviews")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/kds/reviews/missing/status").contentType("application/json").content(json.writeValueAsString(Map.of("status","HIDDEN")))).andExpect(status().isNotFound());
        var member=new User();member.setRole("MEMBER");when(mongoUsers.findByEmailIgnoreCase("fixture-admin@example.com")).thenReturn(Optional.of(member));when(jwt.validateTokenForUser("admin-fixture",member)).thenReturn(true);
        mvc.perform(get("/api/kds/reviews").header("Authorization","Bearer admin-fixture")).andExpect(status().isNotFound());
    }
    @Test void callbackObservesCommittedBothRecordsAndSocketFailureStillReturnsSuccess() {
        var order=createOrder();String id=order.getOrder().getId();var calls=new AtomicInteger();
        var result=reviewStore.create(id,null,order.getTrackingToken(),requestReview(),snapshot->{
            assertThat(jdbc.queryForObject("SELECT reviewed FROM customer_orders WHERE id=?",Boolean.class,id)).isTrue();assertThat(count("order_reviews")).isEqualTo(1);assertThat(snapshot.isReviewed()).isTrue();calls.incrementAndGet();
        });assertThat(result.status()).isEqualTo(200);assertThat(calls).hasValue(1);
        var second=createOrder();var room=sockets.getRoomOperations("kds");doThrow(new IllegalStateException("socket unavailable")).when(room).sendEvent(anyString(),any());submit(second);assertThat(count("order_reviews")).isEqualTo(2);
    }
    @Test void deferredCommitFailurePublishesNothingAndRollsBackFlagAndChildren() {
        var order=createOrder();reset(sockets);
        jdbc.execute("CREATE FUNCTION reject_review_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled commit failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_review_commit AFTER UPDATE ON customer_orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_review_commit()");
        try {assertThatThrownBy(()->submit(order)).isInstanceOf(RuntimeException.class);assertThat(count("order_reviews")).isZero();assertThat(count("order_review_items")).isZero();assertThat(checkout.findById(order.getOrder().getId()).orElseThrow().isReviewed()).isFalse();verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());}
        finally {jdbc.execute("DROP TRIGGER reject_review_commit ON customer_orders");jdbc.execute("DROP FUNCTION reject_review_commit()");}
    }

    @Test void reviewHoldsParentLockUntilCommitThenWaitingDuplicateConflicts() throws Exception {
        var order=createOrder();String id=order.getOrder().getId();reset(sockets);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var callbacks=new AtomicInteger();
        var delegate=mock(com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository.class,org.mockito.AdditionalAnswers.delegatesTo(orders));
        doAnswer(call->{var found=orders.findForMessageAppend(id);locked.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return found;}).when(delegate).findForMessageAppend(id);
        var held=new PostgresReviewStore(delegate,reviews,checkout,access,context.getBean("orderJpaTransactionManager",org.springframework.orm.jpa.JpaTransactionManager.class),java.time.Clock.systemDefaultZone());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->held.create(id,null,order.getTrackingToken(),requestReview(),snapshot->callbacks.incrementAndGet()));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->reviewController.createReview(id,null,order.getTrackingToken(),requestReview()));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
            while(System.nanoTime()<end) {waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class);if(waiting>0)break;Thread.sleep(20);}
            assertThat(waiting).isPositive();assertThat(second.isDone()).isFalse();assertThat(count("order_reviews")).isZero();
            assertThat(jdbc.queryForObject("SELECT reviewed FROM customer_orders WHERE id=?",Boolean.class,id)).isFalse();assertThat(callbacks).hasValue(0);
            release.countDown();assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(second.get(10,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
            assertThat(callbacks).hasValue(1);assertThat(count("order_reviews")).isEqualTo(1);
            verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());
        } finally {release.countDown();pool.shutdownNow();}
    }
}
