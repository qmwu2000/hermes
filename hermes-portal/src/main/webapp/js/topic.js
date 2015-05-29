function normalize_topics(topics, filter, table_state) {
	var topic_rows = [];
	for (var i = 0; i < topics.length; i++) {
		var row = angular.copy(topics[i]);
		row.partitions = row.partitions.length;
		topic_rows.push(row);
	}

	topic_rows = table_state.search.predicateObject ? filter('filter')(topic_rows, table_state.search.predicateObject) : topic_rows;
	if (table_state.sort.predicate) {
		topic_rows = filter('orderBy')(topic_rows, table_state.sort.predicate, table_state.sort.reverse);
	}

	return topic_rows;
}

angular.module('hermes-topic', [ 'smart-table', 'topic-services' ]).controller('topic-controller',
		[ '$scope', '$filter', 'TopicService', function(scope, filter, TopicService) {
			scope.is_loading = true;
			scope.src_topics = [];
			scope.topic_rows = [];
			scope.get_topics = function get_topics(table_state) {
				TopicService.query().$promise.then(function(data) {
					scope.src_topics = data;
					scope.topic_rows = normalize_topics(scope.src_topics, filter, table_state);
					scope.is_loading = false;
				});
			};
			scope.add_topic = function add_topic(new_topic) {
				console.log(new_topic);
			};
		} ]);
