Fs3{
	//uri
	classvar <uri_base = "http://www.freesound.org/apiv2";
	classvar <uri_text_search = "/search/text/";
	classvar <uri_content_search = "/search/content/";
	classvar <uri_sound = "/sounds/%/";
	classvar <uri_sound_similar = "/sounds/%/similar/";
	classvar <uri_sound_analysis = "/sounds/%/analysis/";
	classvar <uri_sound_download = "/sounds/%/download/";

	//authentication
	classvar <api_key = "3c7f3bbe2cb650ec4c92389b4097dfe15aa16e54";
	classvar <api_id = "ebc822b86af0dbdc01e4";
	classvar <token_prefix = "token=";
	classvar <authorization = "";
	classvar <access_token = "";

	// defaults
	classvar <>local_dir;
	classvar <response_fields_base = "name,download,id,type,previews,similar_sounds";
	classvar <>response_fields = "duration,avg_rating,tags,description";
	classvar <>default_base_filter = "type:(wav OR aif OR aiff) license:(\"Attribution\" OR \"Creative Commons 0\")";
	classvar <>report_fields = "name,duration,avg_rating,tags,description";
	classvar <>group_by_pack = 0;
	classvar <>page_size = 20;
	classvar <>sort = "score";
	classvar <>descriptors;
	classvar <>normalize = 0;

	var results_file;

	*initClass{
		local_dir = PathName.tmp;
	}

	*url_encode{|string|
		var str="";
		// url-friendly: hex for spaces and special chars
		string.do({|c|
			if(c.isAlphaNum || (c == $?) || (c == $=) || (c == $&) )
			{str = str++c}
			{str=str++"%"++c.ascii.asHexString(2)}
		})
		^str;
	}

	*token{
		^(token_prefix ++ api_key);
	}

	*oauth2{
		^"-H 'Authorization: Bearer %'".format(Fs3.access_token);
	}

	*text_search_options{
		var options_dict = ();
		options_dict.putAll(
			(\page_size: page_size,
			\group_by_pack: group_by_pack,
			\sort: sort
			);
		);
		if(descriptors != nil, {options_dict.put(\descriptors, descriptors)});
		^options_dict;
	}


	*parse{arg json_string;
		var parsed = json_string;
		var a,x;

		parsed = parsed.tr($', Char.space);

		json_string.do({|char,pos|
			var inString = false;

			char.switch(
				// if it's not \", it opens or closes a string
				$",{(json_string[pos-1]==$\ && inString).not.if({inString = inString.not})},
				${,{ if(inString.not){parsed[pos] = $(} },
				$},{ if(inString.not){parsed[pos] = $)} }
			)
		});

		parsed = parsed.replace("null", "nil");
		parsed = parsed.tr($", $');
		parsed = parsed.replace("next", "next_page");
		// because the resulting event already has a method called next
		parsed = parsed.replace("previous", "prev_page"); // for consistency
		parsed = parsed.replace("count", "result_count");
		^parsed.interpret;
	}

	*new_target_filepath{var file;
		file = Fs3.local_dir ++ "fs3_"++UniqueID.next++".txt";
		^file;
	}

	*report_field_list {
		var fields;
		fields = Fs3.report_fields.split($,);
		fields = fields.collect{|x| x.stripWhiteSpace.asSymbol};
		^fields;
	}

	*response_fields_clean {
		var fields;
		fields = [Fs3.response_fields_base, Fs3.response_fields].join($,).split($,);
		fields = fields.collect{|x| x.stripWhiteSpace};
		fields = fields.join($,);
		^fields;
	}

	*request_format {|params_dict|
		var param_string, param_list;
		param_list = List.new;
		params_dict.keysValuesDo{arg key, value;
			if (value != nil, {
				param_list.add(key.asString ++ "=" ++ value);
			})
		};
		param_list = param_list.join($&);
		param_string = "?%".format(param_list);
		^param_string;
	}

	*authorize{arg auth_code = 0;
		var cmd, target_file;
		if (auth_code == 0,
			{
				cmd = "open 'https://www.freesound.org/apiv2/oauth2/authorize/?client_id=%&response_type=code&state=quark'".format(api_id);
				cmd.unixCmd({arg result; result.postln;});},
			{
				target_file = Fs3.new_target_filepath;
				authorization = auth_code;
				cmd = "curl -X POST -d 'client_id=%&client_secret=%&grant_type=authorization_code&code=%' https://www.freesound.org/apiv2/oauth2/access_token/ > %".format(api_id, api_key, auth_code, target_file);
				cmd.unixCmd({arg result;
					if (result == 0, {
					var file, dict;
					file = File.new(target_file, "r");
					dict = this.parse(file.readAllString).postln;
					access_token = dict.at('access_token');
						"access_token saved".postln;
					}, {"Failed.".postln;});
				});

			}
		);
	}

	text_search{arg query, filter,page=1;
		var target_file = Fs3.new_target_filepath;
		Fs3query.text_search(query, filter, page, target_file, {|results| results_file = target_file;
			"Search complete.  Use .results".postln;
		})
	}

	content_search{arg target, descriptors_filter, page=1;
		var target_file = Fs3.new_target_filepath;
		Fs3query.content_search(target, descriptors_filter, page, target_file,
			{results_file = target_file;
			"Search complete.  Use .results".postln;
		})

	}

	results{
		^Fs3results(results_file);
	}

	*map{
		"open http://itouchmap.com/latlong.html".unixCmd;
	}
}


Fs3query{ // must be a separate class because unixcmd is asynchronous

	*text_search{arg query, filter, page=1, target_file, action;
		var curl, uri, request;

		if (filter == nil, {filter = ""}); // more elegant option?

		request = (
			\query: query,
			\filter: filter + Fs3.default_base_filter,
			\page: page,
			\fields: Fs3.response_fields_clean,
			\token: Fs3.api_key
		) ++ Fs3.text_search_options;

		if (request.query == nil, {request.removeAt(\query)}); // query is optional

		request = Fs3.request_format(request);
		request = Fs3.url_encode(request);
		uri = "%%%".format(Fs3.uri_base, Fs3.uri_text_search, request);
		curl = "curl '%' > %".format(uri, target_file);
		curl.postln;
		curl.unixCmd(action);
	}

	*content_search{arg target, descriptors_filter, page=1, target_file, action;
		var curl, uri, request;

		request = (
			\target: target,
			\descriptors_filter: descriptors_filter,
			\page: page,
			\fields: Fs3.response_fields_clean,
			\token: Fs3.api_key
		) ++ Fs3.text_search_options;

		if (request.target == nil, {request.removeAt(\target)}); // target is optional

		request = Fs3.request_format(request);
		request = Fs3.url_encode(request);
		uri = "%%%".format(Fs3.uri_base, Fs3.uri_content_search, request);
		curl = "curl '%' > %".format(uri, target_file);
		curl.postln;
		curl.unixCmd(action);
	}

	*get_page{arg uri,target_file,options,action;
		// from freesound generated uri (often already includes query, page, fields, filters etc) -- ie next page, similar sounds, pack sounds,
		var curl, token, prefix;
		if(uri.asString.find("?") != nil, {prefix = "&"}, {prefix = "?"});
		token = prefix ++ "token=" ++ Fs3.api_key;

		if (options != nil, {
			options = (
			\filter: Fs3.default_base_filter,
			\fields: Fs3.response_fields_clean,
		) ++ Fs3.text_search_options;
			options = Fs3.request_format(options);
			options = Fs3.url_encode(options.replaceAt("&", 0));
		}, options = "");

		curl = "curl '%%%' > %".format(uri,token,options,target_file);
		curl.postln;
		curl.unixCmd(action);
	}

	*get_sound{arg uri,target,action;
		var curl;
		curl = "curl % '%' > '%'".format(Fs3.oauth2,uri,target);
		curl.postln;
		curl.unixCmd(action);
	}

	*get_sound_info{arg id, target, action; // use new request_format
		var curl, uri;
		uri = Fs3.uri_sound.format(id);
		curl = "curl '%%?%' > '%'".format(Fs3.uri_base, uri, Fs3.token, target);
		curl.postln;
		curl.unixCmd(action);
	}

	*get_sound_analysis{arg id, target, action;// use new request_format
		var curl, uri;
		uri = Fs3.uri_sound_analysis.format(id);
		curl = "curl '%%?%' > '%'".format(Fs3.uri_base, uri, Fs3.token, target);
		curl.postln;
		curl.unixCmd(action);
	}

	*get_pack{arg uri,target,action;
		var curl;
		curl = "curl % '%' > '%'".format(Fs3.oauth2,uri,target);
		curl.postln;
		curl.unixCmd(action);
	}

}


Fs3results : List {
	var <dict, <file, <array;

	*new{arg results_file_path;
		^super.new.init(results_file_path);
	}

	init{arg results_file_path;
		file = File.new(results_file_path, "r");
		dict = Fs3.parse(file.readAllString).postln;
		array = dict.results.collect{|result| Fs3sound.new(result)};
		this.report;
	}

	drop{arg index;
		if (index.class == Array, {
			var items_to_remove = this.at(index);
			items_to_remove.do{arg i;
				this.remove(i);
			}
			},
			{
				^super.drop(index); // fix
			}
		)

	}

	next_page {
		var target_file = Fs3.new_target_filepath;
		var uri = this.dict.next_page.asString;
		target_file.postln;uri.postln;
		Fs3query.get_page(uri,target_file,action: {this.init(target_file)})
	}

	prev_page {
		var target_file = Fs3.new_target_filepath;
		var uri = this.dict.prev_page.asString;
		target_file.postln;uri.postln;
		Fs3query.get_page(uri,target_file,action: {this.init(target_file)})
	}

	report {
		var report_fields;
		Char.nl.postln;
		"% results".format(dict['result_count']).underlined.postln;
		this.do{|result, i| result.report(i);};
		Char.nl.postln;
	}

	dl {arg which = 0;
		if (which.class != Array, {which = Array.with(which)});
		this.at(which).do{arg sound;
			sound.dl;
		}
	}

	get_previews {
		Task.new{
			this.do{arg sound;
				sound.get_preview;
				0.3.wait; // seems to decrease curl failures...
			}
		}.play
	}

	preview {arg item;
		this[item].preview.play;
		^this[item].report;
	}

	previewer {
		^Routine({
			this.do{arg item, i;
				"Previewing %: %".format(i, item.name).postln;
				{item.preview.play.yield}.try({arg ex; "File error or preview not yet downloaded.  Error: %".format(ex)});
			}
		})
	}

	buffers {
		^this.collect{arg item; if (item.buffer != nil, {item.buffer})};
	}
}



Fs3sound : Event {
	var has_full_report = false, has_analysis = false;

	*new{arg sound_data;
		^super.new.init(sound_data);
	}

	init{arg sound_data;
		this.putAll(sound_data)
	}

	report {arg i;
		var report_fields, result_string = "";
		if (i.class == Integer, {result_string = "[%]".format(i)});
		report_fields = Fs3.report_field_list;
		report_fields.do{arg field;
			result_string = result_string + this.at(field).asString;
		};
		^result_string.postln;
	}

	get_full_report {
		if (has_full_report.not, {
			var target = Fs3.new_target_filepath;
			Fs3query.get_sound_info(this.id, target, {
				var file, sound_data;
				file = File.new(target, "r");
				sound_data = Fs3.parse(file.readAllString).postln;
				has_full_report = true;
				this.init(sound_data);
			});
		}, {"full report already downloaded".postln}
		);
	}

	full_report {
		if (has_full_report, {
			Char.nl.postln;
			"Sound info:".underlined.postln;
			this.keysValuesDo{|key, value|
				"-%: %".format(key.asString, value.asString).postln;
			};
			Char.nl.postln;
		}, {"use get_full_report first".postln})

	}

	get_analysis {
		if (has_analysis.not, {
			var target = Fs3.new_target_filepath;
			Fs3query.get_sound_analysis(this.id, target, {
				var file, analysis_data;
				file = File.new(target, "r");
				analysis_data = Fs3.parse(file.readAllString).postln;
				has_analysis = true;
				this.analysis = analysis_data;
				}
			)
			}
		)
	}

	analysis_report {
		var data = this.analysis;
		// so ugly -- rewrite with recursive function
		data.keysValuesDo{arg key, value;
			"".postln;
			key.asString.underlined.postln;
			if (value.class == Event, {
				value.keysValuesDo{arg key1, value1;
					"".postln;
					("--" ++ key1.asString).postln;
					if (value1.class == Event, {
						value1.keysValuesDo{arg key2, value2;
							("" ++ key2.asString).postln;
							if (value2.class == Event, {
								value2.keysValuesDo{arg key3, value3;
									"%: %".format(key3, value3).postln;
								}}, {
								(value2.asString).postln
							})
						}
					}, {
					("" ++ value1.asString).postln;
					})
				}


			}, {("-" ++ value).postln;})
		}
	}

	get_preview {
		var target, filename, uri;
		uri = this.previews['preview-lq-mp3'];
		filename = PathName.new(uri.asString).fileName;
		target = Fs3.local_dir ++ filename;
		this.preview_path = target;
		Fs3query.get_sound(uri, target, {
			this.preview = MP3.readToBuffer(Server.default, target,action: {"preview loaded.  use this.preview.play to listen".postln;})
		});
	}

	get_similar {
		var uri = this.similar_sounds;
		var target_file = Fs3.new_target_filepath;
		Fs3query.get_page(uri, target_file,action: {this.similar = Fs3results.new(target_file)})
	}

	get_more_from_same_pack {
		var uri = this.more_from_same_pack;
		var target_file = Fs3.new_target_filepath;
		Fs3query.get_page(uri, target_file,action: {this.more_from_same_pack = Fs3results.new(target_file)})
	}

	get_pack {
		var uri = this.pack;
		var target_file = Fs3.new_target_filepath;
		Fs3query.get_page(uri, target_file,action: {this.pack = Fs3pack.new(target_file)})
	}

	dl {
		// better: check if the file is there first, rather than if it has a \path
		var target, path;

		target = Fs3.local_dir ++ this.name;

		// add extension if needed
		if(PathName(target).extension != this.type.asString,
			{target = target ++ "." ++ this.type});

		// check in /tmp/ -- don't download if you don't need to
		if (File.exists(target).not, {

			Fs3query.get_sound(this.download, target, {
				this.buffer = Buffer.read(Server.default, this.path);
			});

			this.path = target;

			}, {
				"loading from drive".postln;
				this.buffer = Buffer.read(Server.default, this.path);

				this.path = target;
		})
	}

	play {
		if (this.buffer != nil, {this.buffer.play});
	}




}



Fs3pack : Event {
	var <file, <array;

	*new{arg results_file_path;
		^super.new.init(results_file_path);
	}

	init{arg results_file_path;
		file = File.new(results_file_path, "r");
		this.putAll(Fs3.parse(file.readAllString));
	}

	// todo
	get_sounds{
		var uri = this.sounds;
		var	file = Fs3.new_target_filepath;
		Fs3query.get_page(this.sounds, file, true, {this.sounds = Fs3results.new(file)})
	}

	get_pack{}
}
