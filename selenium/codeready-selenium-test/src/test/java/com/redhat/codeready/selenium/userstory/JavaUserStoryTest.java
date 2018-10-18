package com.redhat.codeready.selenium.userstory;

import static com.redhat.codeready.selenium.pageobject.dashboard.CodereadyNewWorkspace.CodereadyStacks.JAVA_EAP;
import static org.eclipse.che.commons.lang.NameGenerator.generate;
import static org.eclipse.che.selenium.core.constant.TestMenuCommandsConstants.Assistant.ASSISTANT;
import static org.eclipse.che.selenium.core.constant.TestMenuCommandsConstants.Assistant.FIND_USAGES;
import static org.eclipse.che.selenium.core.constant.TestMenuCommandsConstants.Assistant.OPEN_DECLARATION;
import static org.eclipse.che.selenium.pageobject.debug.DebugPanel.DebuggerActionButtons.BTN_DISCONNECT;
import static org.eclipse.che.selenium.pageobject.debug.DebugPanel.DebuggerActionButtons.EVALUATE_EXPRESSIONS;
import static org.eclipse.che.selenium.pageobject.debug.DebugPanel.DebuggerActionButtons.RESUME_BTN_ID;
import static org.eclipse.che.selenium.pageobject.debug.DebugPanel.DebuggerActionButtons.STEP_INTO;
import static org.eclipse.che.selenium.pageobject.debug.DebugPanel.DebuggerActionButtons.STEP_OUT;
import static org.eclipse.che.selenium.pageobject.debug.DebugPanel.DebuggerActionButtons.STEP_OVER;
import static org.openqa.selenium.Keys.ALT;
import static org.openqa.selenium.Keys.ARROW_LEFT;
import static org.openqa.selenium.Keys.CONTROL;
import static org.openqa.selenium.Keys.F4;
import static org.testng.Assert.assertEquals;

import com.google.inject.Inject;
import com.redhat.codeready.selenium.pageobject.RhDebuggerPanel;
import com.redhat.codeready.selenium.pageobject.dashboard.CodereadyNewWorkspace;
import com.redhat.codeready.selenium.pageobject.dashboard.RhFindUsagesWidget;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.stream.Stream;
import javax.ws.rs.HttpMethod;
import org.eclipse.che.api.core.rest.HttpJsonRequestFactory;
import org.eclipse.che.selenium.core.client.TestWorkspaceServiceClient;
import org.eclipse.che.selenium.core.constant.TestMenuCommandsConstants;
import org.eclipse.che.selenium.core.user.DefaultTestUser;
import org.eclipse.che.selenium.core.webdriver.SeleniumWebDriverHelper;
import org.eclipse.che.selenium.core.workspace.TestWorkspaceProvider;
import org.eclipse.che.selenium.pageobject.CodenvyEditor;
import org.eclipse.che.selenium.pageobject.Consoles;
import org.eclipse.che.selenium.pageobject.Events;
import org.eclipse.che.selenium.pageobject.Menu;
import org.eclipse.che.selenium.pageobject.NotificationsPopupPanel;
import org.eclipse.che.selenium.pageobject.ProjectExplorer;
import org.eclipse.che.selenium.pageobject.Wizard;
import org.eclipse.che.selenium.pageobject.dashboard.AddOrImportForm;
import org.eclipse.che.selenium.pageobject.dashboard.Dashboard;
import org.eclipse.che.selenium.pageobject.dashboard.workspaces.WorkspaceDetails;
import org.eclipse.che.selenium.pageobject.dashboard.workspaces.WorkspaceOverview;
import org.eclipse.che.selenium.pageobject.dashboard.workspaces.Workspaces;
import org.eclipse.che.selenium.pageobject.debug.JavaDebugConfig;
import org.eclipse.che.selenium.pageobject.intelligent.CommandsPalette;
import org.openqa.selenium.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** @author Musienko Maxim */
public class JavaUserStoryTest {
  private static final Logger LOG = LoggerFactory.getLogger(JavaUserStoryTest.class);
  private final String WORKSPACE = generate("JavaUserStory", 4);
  private final String PROJECT = "kitchensink-example";
  private final String PATH_TO_MAIN_PACKAGE =
      PROJECT + "/src/main/java/org/jboss/as/quickstarts/kitchensink";
  @Inject private Dashboard dashboard;
  @Inject private WorkspaceDetails workspaceDetails;
  @Inject private Workspaces workspaces;
  @Inject private WorkspaceOverview workspaceOverview;
  @Inject private CodereadyNewWorkspace newWorkspace;
  @Inject private DefaultTestUser defaultTestUser;
  @Inject private TestWorkspaceProvider testWorkspaceProvider;
  @Inject private SeleniumWebDriverHelper seleniumWebDriverHelper;
  @Inject private ProjectExplorer projectExplorer;
  @Inject private TestWorkspaceServiceClient workspaceServiceClient;
  @Inject private AddOrImportForm addOrImportForm;
  @Inject private CommandsPalette commandsPalette;
  @Inject private Wizard wizard;
  @Inject private Consoles consoles;
  @Inject private CodenvyEditor editor;
  @Inject private HttpJsonRequestFactory requestFactory;
  @Inject private Menu menu;
  @Inject private RhDebuggerPanel debugPanel;
  @Inject private JavaDebugConfig debugConfig;
  @Inject private Events events;
  @Inject private NotificationsPopupPanel notifications;
  @Inject private RhFindUsagesWidget findUsages;

  private String appUrl;

  @BeforeClass
  public void setUp() {
    dashboard.open();
  }

  @AfterClass
  public void tearDown() throws Exception {
    workspaceServiceClient.delete(WORKSPACE, defaultTestUser.getName());
  }

  @Test(priority = 1)
  public void createJavaEAPWorkspaceWithProjectFromDashBoard() throws Exception {
    createWsFromJavaEAPStackWithTestProject(PROJECT);
  }

  // @Test(priority = 2)
  public void checkMainDebuggerFeatures() throws Exception {
    setUpDebugMode();
    projectExplorer.openItemByPath(PATH_TO_MAIN_PACKAGE + "/data/MemberListProducer.java");
    editor.setBreakPointAndWaitActiveState(30);
    doGetRequestToApp();
    debugPanel.waitDebugHighlightedText("return members;");
    checkEvaluateExpression();
    checkStepInto();
    checkStepOver();
    checkStepOut();
    checkFramesAndVariablesWithResume();
    checkEndDebugSession();
  }

  @Test(priority = 3)
  public void checkCodeAssistantFeatures() {
    projectExplorer.openItemByPath(PATH_TO_MAIN_PACKAGE + "/controller/MemberRegistration.java");
    editor.waitActive();
    editor.goToPosition(39, 14);
    editor.typeTextIntoEditor(F4.toString());
    editor.waitActiveTabFileName("Member");
    editor.waitCursorPosition(23, 14);

    menu.runCommand(ASSISTANT, FIND_USAGES);
    findUsages.waitExpectedOccurences(21);
    menu.runCommand(TestMenuCommandsConstants.Edit.EDIT,  "gwt-debug-topmenu/Edit/switchLeftTab");
    editor.waitActiveTabFileName("MemberRegistration");
    editor.waitActive();
    editor.goToPosition(36, 7);
    menu.runCommand(ASSISTANT, OPEN_DECLARATION);
    editor.waitActiveTabFileName("Inject");
    editor.waitTextIntoEditor("public @interface Inject");
    editor.clickOnDownloadSourcesLink();
    editor.waitTextIntoEditor("Copyright (C) 2009 The JSR-330 Expert Group");
    editor.waitTextIntoEditor(
        "Identifies injectable constructors, methods, and fields. May apply to static");
    editor.selectTabByName("MemberRegistration");
    editor.goToPosition(28, 14);
    editor.typeTextIntoEditor(CONTROL.toString() + "q");
    editor.waitTextInJa("java.util.logging.Logger");


  }

  private void setUpDebugMode() {
    commandsPalette.openCommandPalette();
    commandsPalette.startCommandByDoubleClick("kitchensink-example:build and run in debug");
    consoles.waitExpectedTextIntoConsole("started in");
    menu.runCommand(
        TestMenuCommandsConstants.Run.RUN_MENU,
        TestMenuCommandsConstants.Run.EDIT_DEBUG_CONFIGURATION);
    debugConfig.createConfig(PROJECT);
    menu.runCommand(
        TestMenuCommandsConstants.Run.RUN_MENU,
        TestMenuCommandsConstants.Run.DEBUG,
        TestMenuCommandsConstants.Run.DEBUG + "/" + PROJECT);
    debugPanel.waitVariablesPanel();
    notifications.waitPopupPanelsAreClosed();
    events.clickEventLogBtn();
    events.waitExpectedMessage("Remote debugger connected");
    consoles.clickOnProcessesButton();
  }

  private void createWsFromJavaEAPStackWithTestProject(String kitchenExampleName) {
    dashboard.selectWorkspacesItemOnDashboard();
    dashboard.waitToolbarTitleName("Workspaces");
    workspaces.clickOnAddWorkspaceBtn();
    newWorkspace.typeWorkspaceName(WORKSPACE);
    newWorkspace.selectCodereadyStack(JAVA_EAP);
    addOrImportForm.clickOnAddOrImportProjectButton();
    addOrImportForm.addSampleToWorkspace(kitchenExampleName);
    newWorkspace.clickOnCreateButtonAndOpenInIDE();
    seleniumWebDriverHelper.switchToIdeFrameAndWaitAvailability();
    projectExplorer.waitItem(kitchenExampleName);
    events.clickEventLogBtn();
    events.waitExpectedMessage("Branch 'master' is checked out");
    projectExplorer.quickExpandWithJavaScript();
  }

  // do request to test application if debugger for the app. has been set properly,
  // expected http response from the app. will be 504, its ok
  private void doGetRequestToApp() {
    appUrl = consoles.getPreviewUrl() + "/index.jsf";
    new Thread(
            () -> {
              try {
                requestFactory.fromUrl(appUrl).useGetMethod().request();
              } catch (Exception e) {
                // if we get 504 response code it is expected
                if (e.getMessage().contains("response code: 504")) {
                  LOG.info("Debugger has been set");
                } else {
                  LOG.error(
                      String.format(
                          "There was a problem with connecting to kitchensink-application for debug on URL '%s'",
                          appUrl),
                      e);
                }
              }
            })
        .start();
  }

  private void checkEvaluateExpression() {
    consoles.clickOnDebugTab();
    debugPanel.clickOnButton(EVALUATE_EXPRESSIONS);
    debugPanel.typeEvaluateExpression("members.size()");
    debugPanel.clickEvaluateBtn();
    debugPanel.waitExpectedResultInEvaluateExpression("1");
    debugPanel.clickCloseEvaluateBtn();
  }

  private void checkStepInto() {
    debugPanel.clickOnButton(STEP_INTO);
    editor.waitTabIsPresent("NativeMethodAccessorImpl");
    debugPanel.waitDebugHighlightedText("return invoke0(method, obj, args);");
  }

  private void checkStepOver() {
    debugPanel.clickOnButton(STEP_OVER);
    editor.waitTabIsPresent("NativeMethodAccessorImpl");
    debugPanel.waitDebugHighlightedText("return delegate.invoke(obj, args);");
  }

  private void checkStepOut() {
    debugPanel.clickOnButton(STEP_OUT);
    editor.waitTabIsPresent("Method");
    debugPanel.waitDebugHighlightedText("return ma.invoke(obj, args);");
  }

  private void checkFramesAndVariablesWithResume() {
    Stream<String> expectedValuesInVariablesWidget =
        Stream.of(
            "em=instance of org.jboss.as.jpa.container.TransactionScopedEntityManager",
            "members=instance of java.util.ArrayList");
    editor.closeAllTabs();
    debugPanel.clickOnButton(RESUME_BTN_ID);
    editor.waitTabIsPresent("MemberListProducer");
    debugPanel.waitDebugHighlightedText("return members;");
    expectedValuesInVariablesWidget.forEach(val -> debugPanel.waitTextInVariablesPanel(val));
    debugPanel.selectFrame(2);
    editor.waitTabIsPresent("NativeMethodAccessorImpl");
  }

  // after stopping debug session the test application should be available again.
  // we check this by UI parts and http request, in this case expected request code should be 200
  private void checkEndDebugSession() throws Exception {
    debugPanel.clickOnButton(BTN_DISCONNECT);
    debugPanel.waitFramesPanelIsEmpty();
    debugPanel.waitVariablesPanelIsEmpty();
    HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(appUrl).openConnection();
    httpURLConnection.setRequestMethod(HttpMethod.GET);
    assertEquals(httpURLConnection.getResponseCode(), 200);
  }
}