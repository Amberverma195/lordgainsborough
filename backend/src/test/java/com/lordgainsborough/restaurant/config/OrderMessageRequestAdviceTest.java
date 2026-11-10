package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.OrderMessageRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.web.server.ResponseStatusException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OrderMessageRequestAdviceTest {
    @Test void unknownLengthBodyIsReadOnlyUpToTheLimit() {
        var read=new AtomicInteger();
        var input=new HttpInputMessage() {
            @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
            @Override public InputStream getBody() {
                return new InputStream() {
                    @Override public int read() {read.incrementAndGet();return 'a';}
                    @Override public int read(byte[] bytes,int offset,int length) {
                        java.util.Arrays.fill(bytes,offset,offset+length,(byte)'a');read.addAndGet(length);return length;
                    }
                };
            }
        };
        var advice=new OrderMessageRequestAdvice(null,null,null,null,null);
        assertThatThrownBy(()->advice.beforeBodyRead(input,null,null,null))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(413));
        assertThat(read.get()).isEqualTo(OrderMessageRateLimiter.MAX_REQUEST_BYTES+1);
    }

    @Test void oversizedContentLengthIsRejectedWithoutReadingTheBody() {
        var headers=new HttpHeaders();headers.setContentLength(OrderMessageRateLimiter.MAX_REQUEST_BYTES+1);
        var input=new HttpInputMessage() {
            @Override public HttpHeaders getHeaders() {return headers;}
            @Override public InputStream getBody() {throw new AssertionError("Must not read oversized body");}
        };
        var advice=new OrderMessageRequestAdvice(null,null,null,null,null);
        assertThatThrownBy(()->advice.beforeBodyRead(input,null,null,null))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(413));
    }
}
