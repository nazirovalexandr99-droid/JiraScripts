import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.issue.search.SearchResults
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import java.util.Random
import org.apache.log4j.Logger

// 1. Инициализация логгера
def log = Logger.getLogger("com.onresolve.jira.groovy.AdvancedAssignee")

// 1.5 Проверяем, не назначен ли уже исполнитель
if (issue.getAssignee()) {
    log.info("Issue ${issue.key} already has assignee: ${issue.getAssignee().getDisplayName()}. Script execution aborted.")
    return  // Прерываем выполнение скрипта
}

// 2. Конфигурация групп исполнителей
Map> userGroups = [
    "Group_Site": ["a.nazirov", "a.nazirov2", "a.nazirov3"],    // Для бэкенд-сайтов
    "Group_App": ["a.nazirov4"],    // Для бэкенд-приложений
    "Group_Button": ["a.nazirov", "a.nazirov5", "a.nazirov7"], // Для фронтенд-кнопок
    "Group_Plates": ["a.nazirov", "a.nazirov2", "a.nazirov8","a.nazirov4","a.nazirov2","a.nazirov9"]  // Для фронтенд-плашек
]

// 3. Получаем сервисы Jira
def customFieldManager = ComponentAccessor.customFieldManager
def userManager = ComponentAccessor.userManager
def issueManager = ComponentAccessor.issueManager
def authContext = ComponentAccessor.jiraAuthenticationContext
SearchService searchService = ComponentAccessor.getComponent(SearchService)
JqlQueryParser jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser)

// 4. Функция для получения количества задач пользователя
int getTaskCountForUser(String username, userManager, searchService, jqlQueryParser, authContext) {
    ApplicationUser user = userManager.getUserByName(username)
    if (!user) {
        log.warn("User ${username} not found")
        return Integer.MAX_VALUE
    }
    
    String jql = "project = TC and status in ('In Progress', 'To Do') and assignee = '${username}'"
    def parseResult = searchService.parseQuery(authContext.loggedInUser, jql)
    
    if (!parseResult.valid) {
        log.warn("Invalid JQL query: ${jql}")
        return Integer.MAX_VALUE
    }
    
    try {
        SearchResults results = searchService.search(authContext.loggedInUser, parseResult.query, PagerFilter.unlimitedFilter)
        return results.getTotal()
    } catch (Exception e) {
        log.error("Error executing JQL query", e)
        return Integer.MAX_VALUE
    }
}

// 5. Получаем значения полей задачи
def directionField = customFieldManager.getCustomFieldObject("customfield_11949")
def directionValue = issue.getCustomFieldValue(directionField)?.toString()?.toLowerCase()

def specializationField = customFieldManager.getCustomFieldObject("customfield_11950")
def specializationValue = issue.getCustomFieldValue(specializationField)?.toString()?.toLowerCase()

def frontSpecializationField = customFieldManager.getCustomFieldObject("customfield_11966")
def frontSpecializationValue = issue.getCustomFieldValue(frontSpecializationField)?.toString()?.toLowerCase()

// 6. Определяем соответствующую группу исполнителей
String selectedGroup = null
String groupDescription = ""
List groupMembers = []

if (directionValue == "backend") {
    switch(specializationValue) {
        case "сайт":
            selectedGroup = "Group_Site"
            groupDescription = "Backend - Сайт"
            groupMembers = userGroups[selectedGroup]
            break
        case "приложение":
            selectedGroup = "Group_App"
            groupDescription = "Backend - Приложение"
            groupMembers = userGroups[selectedGroup]
            break
        default:
            groupDescription = "Backend - специализация не определена"
    }
} else if (directionValue == "frontend") {
    switch(frontSpecializationValue) {
        case "кнопки":
            selectedGroup = "Group_Button"
            groupDescription = "Frontend - Кнопки"
            groupMembers = userGroups[selectedGroup]
            break
        case "плашки":
            selectedGroup = "Group_Plates"
            groupDescription = "Frontend - Плашки"
            groupMembers = userGroups[selectedGroup]
            break
        default:
            groupDescription = "Frontend - специализация не определена"
    }
} else {
    groupDescription = "Направление не выбрано"
}

// 7. Выбираем наименее загруженного исполнителя
ApplicationUser assignee = null
String assigneeInfo = ""
if (groupMembers && !groupMembers.empty) {
    // Собираем данные о загрузке
    Map userWorkload = [:]
    groupMembers.each { username ->
        userWorkload[username] = getTaskCountForUser(username, userManager, searchService, jqlQueryParser, authContext)
    }
    
    log.debug("User workload: ${userWorkload}")
    
    // Находим минимальную загрузку и всех кандидатов с такой загрузкой
    List candidates = []
    Integer minWorkload = Integer.MAX_VALUE
    
    userWorkload.each { username, workload ->
        if (workload < minWorkload) {
            // Нашли нового "лидера" - сбрасываем список кандидатов
            minWorkload = workload
            candidates = [username]
        } else if (workload == minWorkload) {
            // Добавляем в список кандидатов
            candidates << username
        }
    }
    
    // Если несколько кандидатов - выбираем случайного
    if (candidates) {
        Random rand = new Random()
        String selectedUser = candidates[rand.nextInt(candidates.size())]
        assignee = userManager.getUserByName(selectedUser)
        
        if (assignee) {
            issue.setAssignee(assignee)
            assigneeInfo = """\n
                |Назначенный исполнитель: ${assignee.getDisplayName()} (${assignee.getUsername()})
                |Загрузка: ${minWorkload} задач
                |Количество кандидатов: ${candidates.size()}
                |Все кандидаты: ${candidates.collect { "${it} (${userWorkload[it]})" }.join(', ')}
                |Загрузка всех: ${userWorkload.collect { "${it.key} (${it.value})" }.join(', ')}""".stripMargin()
        } else {
            assigneeInfo = "\nОшибка: не удалось найти пользователя ${selectedUser}"
        }
    }
}

// 8. Формируем и обновляем описание
String currentDescription = issue.getDescription() ?: ""
String newDescription = """${currentDescription}
    |${currentDescription ? '\n' : ''}
    |${groupDescription}""".stripMargin()

if (selectedGroup) {
    newDescription += """\n
        |Группа: ${selectedGroup}
        |Участники: ${groupMembers.join(', ')}""".stripMargin()
}

newDescription += assigneeInfo ?: "\nИсполнитель не назначен (не удалось определить группу)"

issue.setDescription(newDescription)

// 9. Сохраняем изменения
issueManager.updateIssue(
    authContext.loggedInUser,
    issue,
    com.atlassian.jira.event.type.EventDispatchOption.ISSUE_UPDATED,
    false
)

log.info("Successfully processed issue ${issue.key}. Assignee: ${assignee?.username}")

