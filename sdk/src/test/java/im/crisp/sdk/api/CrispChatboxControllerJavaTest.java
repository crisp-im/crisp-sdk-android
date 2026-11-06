package im.crisp.sdk.api;

/**
 * Checks at compile time that Java sees the callback overloads, with optional completions. Never
 * run, as calls need a main dispatcher.
 */
@SuppressWarnings("unused")
public class CrispChatboxControllerJavaTest {
  private void callsCompileWithoutCompletion(CrispChatboxController controller) {
    controller.getHelpdesk().search();
    controller.getHelpdesk().search(() -> {});
    controller.getHelpdesk().openArticle("en", "getting-started");
    controller.getUser().getEmail(email -> {});
  }

  private void settersCompileWithDefaults(CrispSessionController controller) {
    controller.getUser().setEmail("john@example.com");
    controller.getUser().setEmail("john@example.com", "signature");
    controller.getSession().setSegments(java.util.List.of("vip"));
    controller.getSession().pushEvent("purchase");
    controller.getSession().reset();
  }
}
