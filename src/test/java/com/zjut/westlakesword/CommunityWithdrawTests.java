package com.zjut.westlakesword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class CommunityWithdrawTests {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Auth auth = mock(Auth.class);
  final MockHttpSession session = new MockHttpSession();

  ContentApi api(long owner, String kind, String status) {
    Store store = new Store(db) {
      @Override public Map<String, Object> one(String sql, Object... args) {
        return Map.of("user_id", owner, "kind", kind, "status", status);
      }
      @Override public void log(long user, String action, Object detail) {}
    };
    when(auth.id(session)).thenReturn(1L);
    return new ContentApi(store, auth);
  }

  @Test void pendingCommentAndWallCanBeWithdrawn() {
    for (String kind : new String[]{"COMMENT", "WALL"}) {
      api(1L, kind, "PENDING").withdrawCommunity(7L, session);
    }
    verify(db, times(2)).update("DELETE FROM posts WHERE id=?", 7L);
  }

  @Test void cannotWithdrawAnotherUsersRecord() {
    assertThrows(ResponseStatusException.class, () -> api(2L, "COMMENT", "PENDING").withdrawCommunity(7L, session));
    verifyNoInteractions(db);
  }

  @Test void reviewedRecordsAndOtherKindsAreProtected() {
    for (String status : new String[]{"APPROVED", "REJECTED"}) {
      assertThrows(ResponseStatusException.class, () -> api(1L, "WALL", status).withdrawCommunity(7L, session));
    }
    assertThrows(ResponseStatusException.class, () -> api(1L, "NEWS", "PENDING").withdrawCommunity(7L, session));
    verifyNoInteractions(db);
  }
}
